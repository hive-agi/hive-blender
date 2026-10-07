(ns hive-blender.addon
  "Host-neutral IAddon with mandatory injected code authorization."
  (:require [hive-addon.protocol :as addon]
            [hive-blender.catalog :as catalog]
            [hive-blender.gate :as gate]
            [hive-blender.port :as port]
            [hive-blender.service :as service]
            [hive-blender.transport.socket :as socket]
            [malli.core :as m]))

(defn tool
  "Construct the consolidated catalog, doctor and command tool."
  [entries link gate]
  {:name "blender"
   :description "Blender base command catalog, doctor and guarded call."
   :inputSchema {:type "object" :required ["command"]
                 :properties {"command" {:type "string" :enum ["catalog" "doctor" "call"]}
                              "id" {:type "string"} "params" {:type "object"}
                              "confirm" {:type "boolean"}}}
   :handler (fn [args]
              (let [field #(or (get args %) (get args (keyword %)))
                    outcome (case (field "command")
                              "catalog" {:ok entries}
                              "doctor" {:ok (service/doctor link gate)}
                              "call" (service/call entries link gate (field "id") (or (field "params") {}) (field "confirm"))
                              {:error {:kind :blender/unknown-tool-command :hint "Choose catalog, doctor or call."}})]
                (if-let [error (:error outcome)]
                  {:isError true :content [{:type "text" :text (str (:kind error) ": " (:hint error))}]}
                  {:content [{:type "text" :text (pr-str (:ok outcome))}]})))})

(defrecord BlenderAddon [state config]
  addon/IAddon
  (addon-id [_] "hive.blender")
  (addon-type [_] :external)
  (capabilities [_] #{:tools :health-reporting})
  (initialize! [_ cfg]
    (let [policy (if (contains? cfg :code-gate) (:code-gate cfg) (:code-gate config))
          code-gate (if (satisfies? port/CodeGate policy) policy (gate/code-gate policy))]
      (if-not code-gate
        {:success? false :errors ["Invalid or missing :code-gate; configure a CodeGate or {:max-bytes n :deny-substrings [...] :require-confirm true}."]}
        (do (reset! state {:gate code-gate
                           :link (or (:link cfg) (:link config)
                                     (socket/socket-link {:port (or (:port cfg) (:port config) 9876)}))
                           :entries (catalog/load-catalog)})
            {:success? true :errors []}))))
  (shutdown! [_] (when-let [link (:link @state)] (port/close! link)) (reset! state {}) nil)
  (tools [_] (if-let [entries (:entries @state)] [(tool entries (:link @state) (:gate @state))] []))
  (schema-extensions [_] {})
  (excluded-tools [_] #{})
  (hooks [_] {})
  (health [_] {:status :degraded :details {:reason :blender/not-probed
                                           :hint "Run doctor against a live Blender GUI; health does not establish readiness."}}))

(defn addon-ctor
  "Build an unmounted addon; initialization requires a CodeGate."
  [config]
  (->BlenderAddon (atom {}) (or config {})))

(m/=> tool [:=> [:cat [:sequential :map] :any :any] :map])
(m/=> addon-ctor [:=> [:cat :map] :any])
