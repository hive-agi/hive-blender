(ns hive-blender.stub
  "Recording in-process BlenderLink and loopback fake add-on server."
  (:require [hive-blender.core :as core]
            [hive-blender.port :as port]
            [clojure.data.json :as json])
  (:import (java.net ServerSocket InetAddress SocketTimeoutException)
           (java.nio.charset StandardCharsets)))

(defrecord StubLink [calls reply-value]
  port/BlenderLink
  (send! [_ command]
    (swap! calls conj command)
    (core/reply reply-value))
  (close! [_] nil))

(defn stub-link [reply-value]
  (->StubLink (atom []) reply-value))

(defrecord StubGate [limit]
  port/CodeGate
  (allow-code? [_ code]
    (and (string? code) (<= (count (.getBytes ^String code "UTF-8")) limit))))

(defn gate [limit] (->StubGate limit))

(defn fake-server
  "Start one loopback request server, reply in chunks without delimiters and return its port/stop function."
  [chunks delay-ms]
  (let [server (ServerSocket. 0 1 (InetAddress/getByName "127.0.0.1"))
        received (promise)
        worker (future
                 (try
                   (with-open [client (.accept server)]
                     (.setSoTimeout client 2000)
                     (let [input (.getInputStream client)
                           bytes (java.io.ByteArrayOutputStream.)]
                       (loop [depth 0 quoted? false escaped? false]
                         (let [b (.read input)
                               ch (char b)
                               opening? (and (not quoted?) (= ch \{))
                               closing? (and (not quoted?) (= ch \}))
                               next-depth (+ depth (if opening? 1 0) (if closing? -1 0))
                               next-quoted? (if (and (= ch \") (not escaped?)) (not quoted?) quoted?)
                               next-escaped? (and quoted? (= ch \\) (not escaped?))]
                           (when (neg? b) (throw (ex-info "Client closed" {})))
                           (.write bytes b)
                           (if (and closing? (zero? next-depth))
                             (deliver received (json/read-str (.toString bytes "UTF-8")))
                             (recur next-depth next-quoted? next-escaped?)))))
                     (Thread/sleep delay-ms)
                     (doseq [chunk chunks]
                       (let [payload (.getBytes ^String chunk StandardCharsets/UTF_8)]
                         (.write (.getOutputStream client) payload)
                         (.flush (.getOutputStream client)))))
                   (catch Exception e
                     (deliver received {:exception (str e)}))))]
    {:port (.getLocalPort server)
     :received received
     :stop (fn [] (.close server) (deref worker 3000 nil))}))
