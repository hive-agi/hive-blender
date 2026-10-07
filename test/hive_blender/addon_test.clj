(ns hive-blender.addon-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-addon.protocol :as addon]
            [hive-blender.addon :as blender]
            [hive-blender.stub :as stub]))

(defn mounted-handler
  "Initialize an addon with a recording link and execute one consolidated tool request."
  [args]
  (let [link (stub/stub-link {"status" "success" "result" "pong"})
        instance (blender/addon-ctor {:link link :code-gate (stub/gate 16)})
        init (addon/initialize! instance {})
        result ((:handler (first (addon/tools instance))) args)]
    (addon/shutdown! instance)
    {:mounted (:success? init) :result result :sent (count @(:calls link))}))

(deftrifecta handler-contract mounted-handler
  {:golden-path "test/golden/addon-handler.edn"
   :cases {:catalog {"command" "catalog"}
           :ping {"command" "call" "id" "ping" "params" {}}
           :code-refused {"command" "call" "id" "execute_code" "params" {"code" "print(1)"}}
           :code-confirmed {"command" "call" "id" "execute_code" "params" {"code" "print(1)"} "confirm" true}
           :unknown {"command" "other"}}
   :gen (gen/elements [{"command" "catalog"} {"command" "call" "id" "ping"}])
   :pred #(and (:mounted %) (map? (:result %))) :num-tests 15
   :mutations [["ignore-request" (fn [_] {:mounted true :result {} :sent 0})]]})

(deftest mandatory-gate-refuses-mount
  (let [instance (blender/addon-ctor {})]
    (is (false? (:success? (addon/initialize! instance {}))))
    (is (empty? (addon/tools instance)))
    (is (= :degraded (:status (addon/health instance))))))
