(ns hive-blender.service
  "Validate intent before crossing the Blender link boundary."
  (:require [hive-blender.core :as core]
            [hive-blender.port :as port]
            [malli.core :as m]
            [hive-blender.stl :as stl]
            [clojure.string]))

(defn call
  "Send a catalogued command; arbitrary code requires a mounted gate and confirmation."
  [entries link gate id params confirm]
  (let [request (core/command entries id params confirm)
        code (or (get params "code") (:code params))]
    (cond
      (:error request) request
      (and (= id "execute_code")
           (or (not (satisfies? port/CodeGate gate))
               (not (string? code))
               (> (count (.getBytes ^String code "UTF-8")) 200000)
               (not (port/allow-code? gate code))))
      {:error {:kind :blender/code-refused :hint "An installed CodeGate must approve code of at most 200000 UTF-8 bytes."}}
      (and (= id "export_scene")
           (not (contains? #{"glb" "fbx"}
                           (let [format (or (get params "format") (:format params))]
                             (if (keyword? format) (name format) format)))))
      {:error {:kind :blender/invalid-export-format :hint "Only GLB and FBX are supported by export_scene."}}
      (not (satisfies? port/BlenderLink link))
      {:error {:kind :blender/unavailable :hint "Start Blender GUI with the add-on on localhost:9876."}}
      :else (port/send! link (:ok request)))))

(defn doctor
  "Probe Blender protocol version 13 over the link; distinguish missing policy and failed transport."
  [link gate]
  (cond
    (not (satisfies? port/CodeGate gate))
    {:status :degraded :reason :blender/code-gate-missing
     :hint "Install a CodeGate before mounting."}
    (not (satisfies? port/BlenderLink link))
    {:status :degraded :reason :blender/link-missing
     :hint "Start a GUI Blender with its add-on listening on localhost:9876."}
    :else
    (let [result (port/send! link {"type" "get_addon_info" "params" {}})]
      (if (= 13 (get (:ok result) "protocol_version"))
        {:status :ready :protocol 13}
        {:status :degraded :reason (or (get-in result [:error :kind]) :blender/protocol-mismatch)
         :hint "Check GUI Blender add-on protocol version 13 and loopback port 9876."}))))

(defn export-stl
  "Export a new binary STL through the installed CodeGate and return a millimetre ModelArtifact."
  [entries link gate request]
  (if-not (try (stl/valid-request? request) (catch Exception _ false))
    {:error {:kind :blender/invalid-request :hint "Use an unused .stl path under user.home, objects and one positive sizing method."}}
    (let [query? (contains? request :target-mm)
          bbox-result (when query? (call entries link gate "execute_code" {"code" (stl/script request 1 true)} true))
          dims (when query? (stl/parse-bbox (get-in bbox-result [:ok "result"])))
          scale (if query? (when (and dims (pos? (apply max dims)))
                             (/ (double (:target-mm request)) (apply max dims)))
                    (double (or (:scale request) 1000)))]
      (cond
        (= :blender/unknown-outcome (get-in bbox-result [:error :kind])) bbox-result
        (and query? (not dims)) {:error {:kind :blender/export-failed :hint "Could not read mesh bbox from Blender."}}
        (or (nil? scale) (not (<= 0.0 scale 1000.0)))
        {:error {:kind :blender/invalid-request :hint "Derived scale exceeds 1000 or mesh has zero extent."}}
        :else
        (let [outcome (call entries link gate "execute_code" {"code" (stl/script request scale false)} true)]
          (if-let [error (:error outcome)]
            {:error (if (= :blender/unknown-outcome (:kind error)) error
                        {:kind :blender/export-failed :hint (:hint error)})}
            (try
              (let [receipt (get-in outcome [:ok "result"])
                    marker (some #(when (clojure.string/starts-with? % "HIVE_EXPORT:") %)
                                 (clojure.string/split-lines (str receipt)))
                    text (when marker (subs marker (count "HIVE_EXPORT:")))
                    separator (when text (clojure.string/last-index-of text "|"))]
                (when-not separator (throw (ex-info "Missing export receipt" {})))
                (let [{:keys [bytes sha256 bbox-mm]} (stl/inspect (:path request))]
                  {:ok {:path (:path request) :format :stl :sha256 sha256 :bytes bytes
                        :units :mm :mm (apply max bbox-mm) :bbox-mm bbox-mm
                        :provenance {:source :blender :objects (vec (clojure.string/split (subs text 0 separator) #","))
                                     :scale scale :blender-version (subs text (inc separator))}}}))
              (catch Exception e
                {:error {:kind :blender/invalid-stl :hint (.getMessage e)}}))))))))

(m/=> export-stl [:=> [:cat [:sequential :map] :any :any :map] :map])

(m/=> call [:=> [:cat [:sequential :map] :any :any :string :map :boolean] :map])
(m/=> doctor [:=> [:cat :any :any] :map])
