(ns hive-blender.service
  "Validate intent before crossing the Blender link boundary."
  (:require [hive-blender.core :as core]
            [hive-blender.port :as port]
            [malli.core :as m]))

(defn call
  "Send a catalogued command; arbitrary code requires a mounted gate and confirmation."
  [entries link gate id params confirm]
  (let [request (core/command entries id params confirm)]
    (cond
      (:error request) request
      (and (= id "execute_code")
           (or (not (satisfies? port/CodeGate gate))
               (not (string? (or (get params "code") (:code params))))
               (> (count (.getBytes ^String (or (get params "code") (:code params)) "UTF-8")) 200000)
               (not (port/allow-code? gate (or (get params "code") (:code params))))))
      {:error {:kind :blender/code-refused :hint "An installed CodeGate must approve code of at most 200000 UTF-8 bytes."}}
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

(m/=> call [:=> [:cat [:sequential :map] :any :any :string :map :boolean] :map])
(m/=> doctor [:=> [:cat :any :any] :map])
