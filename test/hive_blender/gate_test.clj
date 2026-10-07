(ns hive-blender.gate-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-addon.protocol :as addon]
            [hive-blender.addon :as blender]
            [hive-blender.gate :as gate]
            [hive-blender.port :as port]
            [hive-blender.stub :as stub]
            [hive-test.trifecta :refer [deftrifecta]]))

(def policy {:max-bytes 32 :deny-substrings ["Import OS" "subprocess"] :require-confirm true})

(defn gate-verdict
  "Evaluate a code string against a configured gate."
  [code]
  (port/allow-code? (gate/code-gate policy) code))

(deftrifecta configured-gate-contract gate-verdict
  {:golden-path "test/golden/configured-gate.edn"
   :cases {:allowed "print(1)" :denied "IMPORT OS" :unicode "🙂🙂🙂🙂🙂🙂🙂🙂🙂"}
   :gen (gen/elements ["print(1)" "IMPORT OS" "subprocess" "🙂🙂🙂🙂🙂🙂🙂🙂🙂"])
   :pred boolean? :num-tests 20
   :mutations [["allow-everything" (fn [_] true)]]})

(defn mount-result
  "Initialize a configured addon with a recording link and report its mount verdict."
  [code-gate]
  (let [instance (blender/addon-ctor {:code-gate code-gate :link (stub/stub-link {})})
        result (addon/initialize! instance {})]
    (addon/shutdown! instance)
    (select-keys result [:success? :errors])))

(deftrifecta config-mount-contract mount-result
  {:golden-path "test/golden/config-mount.edn"
   :cases {:absent nil :invalid {:max-bytes 0 :deny-substrings [] :require-confirm false}
           :configured policy}
   :gen (gen/elements [nil policy {:max-bytes 0 :deny-substrings [] :require-confirm false}])
   :pred #(boolean? (:success? %)) :num-tests 15
   :mutations [["mount-everything" (fn [_] {:success? true :errors []})]]})

(deftest policy-refusal-and-injection
  (is (re-find #":code-gate" (first (:errors (mount-result nil)))))
  (is (false? (:success? (mount-result (assoc policy :require-confirm false)))))
  (is (false? (:success? (mount-result (assoc policy :deny-substrings [])))))
  (is (false? (:success? (mount-result (assoc policy :max-bytes 200001)))))
  (is (:success? (mount-result (stub/gate 16))))
  (is (:success? (mount-result policy)))
  (let [instance (blender/addon-ctor {:code-gate policy :port 12345})]
    (is (:success? (addon/initialize! instance {})))
    (is (= 12345 (get-in @(:state instance) [:link :config :port])))
    (addon/shutdown! instance)))
