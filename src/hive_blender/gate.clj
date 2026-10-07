(ns hive-blender.gate
  "Validated data-driven authorization for Blender code execution."
  (:require [clojure.string :as str]
            [hive-blender.port :as port]
            [malli.core :as m]))

(def GateConfig
  [:map {:closed true}
   [:max-bytes [:int {:min 1 :max 200000}]]
   [:deny-substrings [:vector {:min 1} [:string {:min 1}]]]
   [:require-confirm [:= true]]])

(defrecord SubstringGate [max-bytes deny-substrings]
  port/CodeGate
  (allow-code? [_ code]
    (and (string? code)
         (<= (count (.getBytes ^String code "UTF-8")) max-bytes)
         (let [lower (str/lower-case code)]
           (not-any? #(str/includes? lower %) deny-substrings)))))

(defn code-gate
  "Build a CodeGate from a closed, confirmed byte-bound substring policy; return nil for invalid data."
  [config]
  (when (m/validate GateConfig config)
    (->SubstringGate (:max-bytes config)
                     (mapv str/lower-case (:deny-substrings config)))))

(m/=> code-gate [:=> [:cat :any] :any])
