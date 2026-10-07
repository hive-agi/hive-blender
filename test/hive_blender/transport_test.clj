(ns hive-blender.transport-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-blender.port :as port]
            [hive-blender.stub :as stub]
            [hive-blender.transport.socket :as socket]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]))

(def ok-reply "{\"status\":\"success\",\"result\":{\"pong\":true}}")
(def error-reply "{\"status\":\"error\",\"message\":\"bad\"}")
(def request {"type" "ping" "params" {}})

(defn- adapters [reply]
  (let [server (stub/fake-server [reply] 0)]
    [[(stub/stub-link (clojure.data.json/read-str reply)) (fn [] nil) nil]
     [(socket/socket-link {:port (:port server) :timeout-ms 250 :max-reply-bytes 4096})
      (:stop server) (:received server)]]))

(deftest blender-link-conformance
  (doseq [[label reply expected] [[:success ok-reply {:ok {"pong" true}}]
                                  [:failure error-reply {:error {:kind :blender/reply-error :hint "bad"}}]
                                  [:nested "{\"status\":\"success\",\"result\":{\"error\":\"oops\"}}"
                                   {:error {:kind :blender/result-error :hint "oops"}}]]]
    (testing (name label)
      (doseq [[link stop received] (adapters reply)]
        (try
          (is (= expected (port/send! link request)))
          (when received (is (= request (deref received 1000 nil))))
          (port/close! link)
          (finally (stop)))))))

(deftest socket-framing-and-bounds
  (doseq [[label chunks delay config expected]
          [[:chunked [(subs ok-reply 0 10) (subs ok-reply 10)] 0 {} :ok]
           [:coalesced [(str ok-reply error-reply)] 0 {} :ok]
           [:oversize [ok-reply] 0 {:max-reply-bytes 18} :blender/oversize-reply]
           [:slow [ok-reply] 200 {:timeout-ms 40} :blender/unknown-outcome]]]
    (testing (name label)
      (let [{:keys [port stop received]} (stub/fake-server chunks delay)
            link (socket/socket-link (merge {:port port :timeout-ms 500} config))]
        (try
          (let [result (port/send! link request)]
            (is (= expected (if (:ok result) :ok (get-in result [:error :kind]))))
            (is (= request (deref received 1000 nil))))
          (finally (port/close! link) (stop)))))))

(defn socket-roundtrip
  "Send a ping through a fresh fake add-on server and return its typed outcome."
  [response]
  (let [{:keys [port stop]} (stub/fake-server [response] 0)
        link (socket/socket-link {:port port :timeout-ms 400})]
    (try (port/send! link request)
         (finally (port/close! link) (stop)))))

(deftrifecta socket-roundtrip-contract socket-roundtrip
  {:golden-path "test/golden/socket-roundtrip.edn"
   :cases {:success ok-reply :failure error-reply}
   :gen (gen/elements [ok-reply error-reply])
   :pred map? :num-tests 12
   :mutations [["blind-success" (fn [_] {:ok nil})]]})
