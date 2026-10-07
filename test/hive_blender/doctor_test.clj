(ns hive-blender.doctor-test
  (:require [clojure.test :refer [deftest is]]
            [hive-blender.service :as service]
            [hive-blender.stub :as stub]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]))

(defn diagnosis
  "Run the doctor with an injectable add-on reply and policy."
  [reply]
  (service/doctor (stub/stub-link reply) (stub/gate 16)))

(deftrifecta doctor-contract diagnosis
  {:golden-path "test/golden/doctor.edn"
   :cases {:ready {"status" "success" "result" {"protocol_version" 13}}
           :bad-version {"status" "success" "result" {"protocol_version" 12}}
           :failure {"status" "error" "message" "offline"}}
   :gen (gen/elements [{"status" "success" "result" {"protocol_version" 13}}
                       {"status" "error" "message" "offline"}])
   :pred #(contains? #{:ready :degraded} (:status %)) :num-tests 15
   :mutations [["always-ready" (fn [_] {:status :ready :protocol 13})]]})

(deftest policy-missing-is-degraded
  (is (= :blender/code-gate-missing
         (:reason (service/doctor (stub/stub-link {"status" "success" "result" {"pong" true}}) nil)))))
