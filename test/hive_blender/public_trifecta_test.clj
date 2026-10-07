(ns hive-blender.public-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-blender.catalog :as catalog]
            [hive-blender.service :as service]
            [hive-blender.addon :as addon]
            [hive-blender.transport.socket :as socket]
            [hive-blender.stub :as stub]
            [hive-blender.port :as port]
            [hive-addon.protocol :as host]))

(def link (stub/stub-link {"status" "success" "result" {"protocol_version" 13}}))
(def gate (stub/gate 64))
(def entries [{:id "ping"}])

(deftrifecta catalog-contract catalog/load-catalog
  {:golden-path "test/golden/catalog-load.edn"
   :cases {:default []} :apply? true
   :gen (gen/return []) :pred #(some (fn [entry] (= "export_scene" (:id entry))) %)
   :num-tests 8 :mutations [["empty" (fn [] [])]]})

(deftrifecta service-call-contract service/call
  {:golden-path "test/golden/service-call.edn"
   :cases {:ping [entries link gate "ping" {} false]
           :missing [entries link gate "missing" {} false]}
   :apply? true :gen (gen/tuple (gen/return entries) (gen/return link) (gen/return gate)
                                (gen/elements ["ping" "missing"]) (gen/return {}) (gen/return false))
   :pred map? :num-tests 8
   :mutations [["always-ok" (fn [& _] {:ok {"protocol_version" 13}})]]})

(deftrifecta service-doctor-contract service/doctor
  {:golden-path "test/golden/service-doctor.edn"
   :cases {:ready [link gate] :no-gate [link nil]}
   :apply? true :gen (gen/tuple (gen/return link) (gen/elements [gate nil]))
   :pred #(contains? #{:ready :degraded} (:status %)) :num-tests 8
   :mutations [["always-ready" (fn [& _] {:status :ready :protocol 13})]]})

(deftrifecta socket-constructor-contract socket/socket-link
  {:golden-path "test/golden/socket-constructor.edn"
   :cases {:default {}}
   :xf #(select-keys (:config %) [:port :timeout-ms :max-reply-bytes])
   :gen (gen/return {}) :pred #(satisfies? port/BlenderLink %) :num-tests 8
   :mutations [["nil-link" (fn [_] nil)]]})

(deftrifecta addon-constructor-contract addon/addon-ctor
  {:golden-path "test/golden/addon-constructor.edn"
   :cases {:empty {} :configured {:code-gate gate}}
   :xf #(vector (host/addon-id %) (boolean (:code-gate (:config %))))
   :gen (gen/elements [{} {:code-gate gate}])
   :pred #(= "hive.blender" (host/addon-id %)) :num-tests 8
   :mutations [["ignore-config" (fn [_] (addon/->BlenderAddon (atom {}) {}))]]})

(deftrifecta addon-tool-contract addon/tool
  {:golden-path "test/golden/addon-tool.edn"
   :cases {:default [entries link gate]}
   :apply? true
   :xf #(select-keys % [:name :description])
   :gen (gen/tuple (gen/return entries) (gen/return link) (gen/return gate))
   :pred #(and (= "blender" (:name %)) (fn? (:handler %))) :num-tests 8
   :mutations [["missing-name" (fn [& _] {:description "Blender base command catalog, doctor and guarded call."})]]})
