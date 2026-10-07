(ns hive-blender.core-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-blender.core :as core]
            [hive-blender.catalog :as catalog]))

(def entries [{:id "ping"} {:id "execute_code"}])

(deftrifecta commands-contract core/commands
  {:golden-path "test/golden/commands.edn"
   :cases {:base entries :empty []}
   :gen (gen/vector (gen/elements entries)) :pred vector? :num-tests 30
   :mutations [["drop-catalog" (fn [_] [])]]})

(deftrifecta command-contract core/command
  {:golden-path "test/golden/command.edn"
   :cases {:known [entries "ping" {} false]
           :unknown [entries "missing" {} false]
           :unconfirmed [entries "execute_code" {} false]
           :confirmed [entries "execute_code" {"code" "print(1)"} true]}
   :apply? true
   :gen (gen/tuple (gen/return entries) (gen/elements ["ping" "execute_code" "missing"])
                   (gen/return {}) gen/boolean)
   :pred map? :num-tests 30
   :mutations [["always-accept" (fn [_ id params _] {:ok {"type" id "params" params}})]]})

(deftrifecta reply-contract core/reply
  {:golden-path "test/golden/reply.edn"
   :cases {:success {"status" "success" "result" 42}
           :failure {"status" "error" "message" "bad"}
           :nested {"status" "success" "result" {"error" "bad"}}
           :malformed {"status" "other"}}
   :gen (gen/hash-map "status" (gen/elements ["success" "error" "other"]))
   :pred map? :num-tests 30
   :mutations [["blind-success" (fn [x] {:ok (get x "result")})]]})

(deftest catalog-is-extracted-base-only
  (let [ids (set (core/commands (catalog/load-catalog)))]
    (is (contains? ids "execute_code"))
    (is (contains? ids "get_addon_info"))
    (is (contains? ids "export_scene"))
    (is (not (contains? ids "create_rodin_job")))
    (is (not (contains? ids "set_telemetry_consent_enabled")))))
