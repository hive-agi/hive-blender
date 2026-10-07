(ns hive-blender.contracts-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [malli.core :as m]
            [hive-blender.contracts]
            [hive-blender.catalog]
            [hive-blender.service]
            [hive-blender.addon]
            [hive-blender.transport.socket]))

(defn- forms [file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (loop [result []]
      (let [form (read {:eof ::end :read-cond :allow :features #{:clj}} r)]
        (if (= ::end form) result (recur (conj result form)))))))

(deftest public-contracts-cover-the-source-tree
  (let [files (->> (file-seq (io/file "src"))
                   (filter #(and (.isFile %) (or (.endsWith (.getName %) ".clj")
                                                 (.endsWith (.getName %) ".cljc")))))
        public (set (for [file files form (forms file)
                          :when (and (seq? form) (= 'defn (first form)))]
                      (symbol (str (second (first (forms file)))) (str (second form)))))
        declared (set (for [file files form (forms file)
                            :when (and (seq? form) (= 'm/=> (first form)))
                            :let [sym (second form) ns-name (second (first (forms file)))]]
                        (if (namespace sym) sym (symbol (str ns-name) (name sym)))))]
    (is (>= (count public) 8) "Read public declarations from actual files, not registry")
    (is (= public declared) (str "missing " (set/difference public declared)
                                 "; stale " (set/difference declared public)))
    (is (every? #(get-in (m/function-schemas) [(symbol (namespace %)) (symbol (name %))]) declared))))
