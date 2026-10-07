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
  "Report local readiness without pretending a health check probes Blender's GUI."
  [link gate]
  {:status (if (and (satisfies? port/BlenderLink link) (satisfies? port/CodeGate gate))
             :ready :degraded)
   :hint "A real Blender GUI and installed add-on are required for end-to-end verification."})

(m/=> call [:=> [:cat [:sequential :map] :any :any :string :map :boolean] :map])
(m/=> doctor [:=> [:cat :any :any] :map])
