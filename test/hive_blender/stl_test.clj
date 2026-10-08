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
            [malli.core :as m])
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
                                    (str/includes? s "_objects ="))) (str/split-lines %)))
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
   :cases {:valid "HIVE_BBOX:1,2,3" :negative "HIVE_BBOX:-1,2,3" :nan "HIVE_BBOX:NaN,2,3"}
   :gen (gen/elements ["HIVE_BBOX:1,2,3" "bad"])
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
        {:ok {"result" "HIVE_BBOX:0.01,0.02,0.03"}}
        (do (Files/copy (.toPath (io/file fixture))
                        (.toPath (io/file (:path request)))
                        (make-array java.nio.file.CopyOption 0))
            {:ok {"result" "HIVE_EXPORT:Cube|4.3.0"}}))))
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
