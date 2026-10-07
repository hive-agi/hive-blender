(ns hive-blender.service-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-blender.port :as port]
            [hive-blender.service :as service]
            [hive-blender.stub :as stub]))

(def entries [{:id "ping"} {:id "execute_code"}])
(def code {"code" "print(1)"})

(defn scenario
  "Run a command against a recording stub link with a mandatory code gate."
  [id params confirm]
  (let [link (stub/stub-link {"status" "success" "result" {"pong" true}})
        result (service/call entries link (stub/gate 16) id params confirm)]
    {:result result :sent (count @(:calls link))}))

(deftrifecta scenario-contract scenario
  {:golden-path "test/golden/scenario.edn"
   :cases {:ping ["ping" {} false]
           :code ["execute_code" code true]
           :unconfirmed ["execute_code" code false]
           :oversize ["execute_code" {"code" (apply str (repeat 17 "a"))} true]
           :unknown ["unknown" {} false]}
   :apply? true
   :gen (gen/tuple (gen/elements ["ping" "execute_code" "unknown"])
                   (gen/return code) gen/boolean)
   :pred #(and (map? (:result %)) (<= (:sent %) 1)) :num-tests 25
   :mutations [["ignore-gate" (fn [_ _ _] {:result {:ok {}} :sent 1})]]})

(deftest code-gate-is-required
  (let [link (stub/stub-link {"status" "success" "result" 1})]
    (is (= :blender/code-refused
           (get-in (service/call entries link nil "execute_code" code true) [:error :kind])))
    (is (= :blender/confirmation-required
           (get-in (service/call entries link (stub/gate 16) "execute_code" code false) [:error :kind])))
    (is (= :blender/code-refused
           (get-in (service/call entries link (stub/gate 200001) "execute_code"
                                 {"code" (apply str (repeat 200001 "a"))} true) [:error :kind])))
    (is (empty? @(:calls link)))))
