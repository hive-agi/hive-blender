(ns extract-catalog
  "Extract the base handler map from the Python AST via Python's ast module, not text matching."
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as sh]))

(defn extract!
  "Invoke the checked-in AST extractor and persist its EDN base catalog."
  [source target]
  (let [{:keys [exit out err]} (sh/sh "python3" "dev/extract_catalog.py" source)]
    (when-not (zero? exit) (throw (ex-info err {:exit exit})))
    (let [entries (edn/read-string out)]
      (when-not (and (vector? entries) (some #(= "execute_code" (:id %)) entries))
        (throw (ex-info "Missing execute_code handler" {:entries entries})))
      (spit target (str (pr-str entries) "\n"))
      entries)))

(defn -main [& [source target]]
  (extract! (or source "../clones-ref/blender-mcp/addon.py")
            (or target "resources/hive_blender/catalog.edn")))
