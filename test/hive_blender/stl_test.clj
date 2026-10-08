(ns hive-blender.stl-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-blender.stl :as stl]
            [hive-blender.service :as service]
            [hive-blender.port :as port]
            [hive-blender.stub :as stub]
            [hive-blender.addon :as addon]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [malli.core :as m]
            [hive-blender.transport.socket])
  (:import (java.nio.file Files Path LinkOption StandardCopyOption)))

(def fixture "test/fixture/triangle.stl")
(def request {:path (str (System/getProperty "user.home") "/stl-export-test.stl") :objects :selected})

(deftrifecta script-contract stl/script
  {:golden-path "test/golden/stl-script.edn"
   :cases {:selected [request 1000 false]
           :all [(assoc request :objects :all) 2 true]
           :named [(assoc request :objects ["Cube" "Box"]) 42 false]}
   :xf #(vec (filter (fn [s] (or (str/includes? s "stl_export")
                                    (str/includes? s "export_mesh.stl")
                                    (str/includes? s "_objects =")))
                     (map (fn [line] (str/replace line (System/getProperty "user.home") "<HOME>"))
                          (str/split-lines %))))
   :apply? true
   :gen (gen/tuple (gen/return request) (gen/elements [1 1000]) gen/boolean)
   :pred #(and (str/includes? % "o.type == 'MESH'")
               (or (str/includes? % "HIVE_BBOX:") (str/includes? % "HIVE_EXPORT:")))
   :num-tests 16
   :mutations [["no-selector" (fn [& _] "print('HIVE_EXPORT:')")]]})

(deftrifecta request-contract stl/valid-request?
  {:golden-path "test/golden/stl-request.edn"
   :cases {:default request :bad-extension (assoc request :path (str (System/getProperty "user.home") "/file.obj"))
           :both (assoc request :scale 1 :target-mm 25)
           :tmp (assoc request :path "/tmp/new.stl")}
   :gen (gen/elements [request (assoc request :scale 0) (assoc request :objects :all)])
   :pred boolean? :num-tests 16
   :mutations [["always-true" (fn [_] true)]]})

(deftrifecta bbox-contract stl/parse-bbox
  {:golden-path "test/golden/stl-bbox.edn"
   :cases {:valid "HIVE_BBOX:[1,2,3]" :negative "HIVE_BBOX:[-1,2,3]" :nan "HIVE_BBOX:[NaN,2,3]"}
   :gen (gen/elements ["HIVE_BBOX:[1,2,3]" "bad"])
   :pred #(or (nil? %) (and (= 3 (count %)) (every? number? %))) :num-tests 16
   :mutations [["always-nil" (fn [_] nil)]]})

(deftrifecta inspect-contract stl/inspect
  {:golden-path "test/golden/stl-inspect.edn"
   :cases {:triangle fixture}
   :gen (gen/return fixture)
   :pred #(and (= 134 (:bytes %)) (= [1.0 2.0 3.0] (:bbox-mm %)) (= 64 (count (:sha256 %))))
   :num-tests 8 :mutations [["wrong-bbox" (fn [_] {:bytes 134 :sha256 (apply str (repeat 64 "0")) :bbox-mm [0 0 0]})]]})

(defrecord RecordingLink [delegate calls]
  port/BlenderLink
  (send! [_ command] (swap! calls conj command) (port/send! delegate command))
  (close! [_] (port/close! delegate)))

(defrecord WritingLink [fixture]
  port/BlenderLink
  (send! [_ command]
    (let [code (get-in command ["params" "code"])]
      (if (str/includes? code "HIVE_BBOX:")
        {:ok {"result" "HIVE_BBOX:[0.01,0.02,0.03]"}}
        (do (Files/copy (.toPath (io/file fixture))
                        (.toPath (io/file (:path request)))
                        (make-array java.nio.file.CopyOption 0))
            {:ok {"result" "HIVE_EXPORT:{\"objects\":[\"Cube\"],\"version\":\"4.3.0\"}"}}))))
  (close! [_] nil))

(defn export-case
  "Exercise the gated export boundary against a recording decorator and a writing link."
  [target?]
  (let [dest (io/file (:path request))
        _ (Files/deleteIfExists (.toPath dest))
        calls (atom [])
        link (->RecordingLink (->WritingLink fixture) calls)
        gate (stub/gate 200000)
        req (cond-> request target? (assoc :target-mm 30))
        result (service/export-stl [{:id "execute_code"}] link gate req)]
    (Files/deleteIfExists (.toPath dest))
    {:result (if-let [artifact (:ok result)] (select-keys artifact [:format :bytes :bbox-mm :mm :provenance]) result)
     :sent (count @calls) :confirmed (every? #(= "execute_code" (get % "type")) @calls)
     :query? (boolean (some #(str/includes? (get-in % ["params" "code"]) "HIVE_BBOX:") @calls))}))

(deftrifecta export-contract export-case
  {:golden-path "test/golden/stl-export.edn"
   :cases {:normal false :target true}
   :gen (gen/elements [false true])
   :pred #(and (= :stl (get-in % [:result :format]))
               (= 134 (get-in % [:result :bytes]))
               (:confirmed %)) :num-tests 8
   :mutations [["no-export" (fn [_] {:result {:format :glb :bytes 0} :sent 0 :confirmed false})]]})

(deftest export-gate-and-confirmation
  (let [calls (atom [])
        approvals (atom [])
        gate (reify port/CodeGate
               (allow-code? [_ code] (swap! approvals conj code) true))
        link (->RecordingLink (stub/stub-link {"status" "success" "result" {"result" "HIVE_EXPORT:{\"objects\":[\"Cube\"],\"version\":\"4.3\"}"}}) calls)
        result (service/export-stl [{:id "execute_code"}] link gate request)]
    (is (= :blender/invalid-stl (get-in result [:error :kind])))
    (is (= 1 (count @approvals)))
    (is (= 1 (count @calls)))
    (is (= (first @approvals) (get-in (first @calls) ["params" "code"])))
    (is (= "execute_code" (get (first @calls) "type")))))

(deftest export-errors-are-typed
  (let [entries [{:id "execute_code"}]
        existing (assoc request :path fixture)
        link (stub/stub-link {"status" "success" "result" {"result" "missing"}})]
    (is (= :blender/invalid-request (get-in (service/export-stl entries link (stub/gate 200000) existing) [:error :kind])))
    (is (empty? @(:calls link)) "An existing destination never dispatches")
    (is (= :blender/export-failed (get-in (service/export-stl entries link (stub/gate 1) request) [:error :kind])))
    (is (= :blender/invalid-stl (get-in (service/export-stl entries link (stub/gate 200000) request) [:error :kind])))))

(deftest validator-rejects-corrupt-geometry
  (let [file (Files/createTempFile (Path/of (System/getProperty "user.home") (make-array String 0)) "stl-invalid" ".stl"
                                    (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (Files/copy (.toPath (io/file fixture)) file (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))
      (is (= 134 (:bytes (stl/inspect (str file)))))
      (let [bytes (Files/readAllBytes file)]
        (aset-byte bytes 80 (byte 2))
        (Files/write file bytes (make-array java.nio.file.OpenOption 0)))
      (is (thrown? Exception (stl/inspect (str file))))
      (finally (Files/deleteIfExists file)))))

(defmacro live-or-skip
  "Run body when the Blender listener is up; otherwise print SKIP and assert absence."
  [& body]
  `(let [socket# (java.net.Socket.)
         up?# (try (.connect socket# (java.net.InetSocketAddress. "127.0.0.1" 9876) 500)
                   true
                   (catch java.io.IOException _# false)
                   (finally (.close socket#)))]
     (if up?#
       (do ~@body)
       (do (println "SKIP Blender GUI listener on 127.0.0.1:9876")
           (is (false? up?#))))))

(deftest live-blender-export
  (live-or-skip
    (let [dest (str (System/getProperty "user.home") "/blender-live-export-" (java.util.UUID/randomUUID) ".stl")
          link (hive-blender.transport.socket/socket-link {:port 9876 :timeout-ms 30000})
          gate (stub/gate 200000)]
      (try
        (let [result (service/export-stl [{:id "execute_code"}] link gate {:path dest :objects :all})]
          (is (nil? (:error result)) (pr-str result))
          (when-let [artifact (:ok result)]
            (is (m/validate stl/ModelArtifact artifact))
            (is (pos? (:mm artifact)))
            (println "LIVE Blender STL" (select-keys artifact [:bytes :sha256 :bbox-mm]))))
        (finally (port/close! link) (Files/deleteIfExists (.toPath (io/file dest))))))))
