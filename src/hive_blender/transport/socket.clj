(ns hive-blender.transport.socket
  "Loopback-only, bounded unframed JSON client; one in-flight call per connection."
  (:require [clojure.data.json :as json]
            [hive-blender.core :as core]
            [hive-blender.port :as port]
            [malli.core :as m])
  (:import (java.io ByteArrayOutputStream EOFException)
           (java.net InetSocketAddress Socket SocketTimeoutException)
           (java.nio.charset StandardCharsets)))

(def default-config {:port 9876 :connect-ms 2000 :timeout-ms 30000 :max-reply-bytes 1048576})

(defn- read-object! [socket limit]
  (let [stream (.getInputStream socket)
        bytes (ByteArrayOutputStream.)]
    (loop [depth 0 quoted? false escaped? false]
      (let [b (.read stream)]
        (when (neg? b) (throw (EOFException. "Blender closed before a complete reply")))
        (when (>= (.size bytes) limit)
          (throw (ex-info "Blender reply exceeds byte limit" {:kind :blender/oversize-reply})))
        (.write bytes b)
        (let [ch (char b)
              opening? (and (not quoted?) (= ch \{))
              closing? (and (not quoted?) (= ch \}))
              next-depth (+ depth (if opening? 1 0) (if closing? -1 0))
              next-quoted? (if (and (= ch \") (not escaped?)) (not quoted?) quoted?)
              next-escaped? (and quoted? (= ch \\) (not escaped?))]
          (when (neg? next-depth)
            (throw (ex-info "Invalid JSON object" {:kind :blender/invalid-reply})))
          (if (and closing? (zero? next-depth))
            (let [parsed (json/read-str (.toString bytes "UTF-8"))]
              (if (map? parsed) parsed
                  (throw (ex-info "Expected JSON object" {:kind :blender/invalid-reply}))))
            (recur next-depth next-quoted? next-escaped?)))))))

(defn- connect! [{:keys [port connect-ms timeout-ms]}]
  (doto (Socket.)
    (.connect (InetSocketAddress. "127.0.0.1" (int port)) (int connect-ms))
    (.setSoTimeout (int timeout-ms))))

(defrecord SocketLink [config socket lock]
  port/BlenderLink
  (send! [_ command]
    (locking lock
      (let [dispatched? (volatile! false)]
        (try
          (let [s (connect! config)
                _ (reset! socket s)
                payload (.getBytes (json/write-str command) StandardCharsets/UTF_8)]
            (vreset! dispatched? true)
            (.write (.getOutputStream s) payload)
            (.flush (.getOutputStream s))
            (core/reply (read-object! s (:max-reply-bytes config))))
          (catch SocketTimeoutException _
            {:error {:kind (if @dispatched? :blender/unknown-outcome :blender/connect-timeout)
                     :hint (if @dispatched? "Timed out after dispatch; do not replay."
                               "Connection timed out before dispatch.")}})
          (catch Exception e
            {:error {:kind (or (:kind (ex-data e))
                               (if @dispatched? :blender/unknown-outcome :blender/transport-error))
                     :hint (or (.getMessage e) "Transport failed; do not replay after dispatch.")}})
          (finally
            (when-let [s @socket] (.close s) (reset! socket nil)))))))
  (close! [_]
    (locking lock
      (when-let [s @socket] (.close s) (reset! socket nil)))))

(defn socket-link
  "Construct a loopback-only link with explicit receive, connect and frame bounds."
  [config]
  (let [cfg (merge default-config config)]
    (when-not (and (<= 1 (:port cfg) 65535) (pos? (:timeout-ms cfg))
                   (pos? (:connect-ms cfg)) (pos? (:max-reply-bytes cfg)))
      (throw (ex-info "Invalid socket bounds" {:config cfg})))
    (->SocketLink cfg (atom nil) (Object.))))

(m/=> socket-link [:=> [:cat :map] [:fn #(satisfies? port/BlenderLink %)]])
