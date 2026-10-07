(ns hive-blender.catalog
  "Read the extracted unconditional add-on command catalog."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]))

(defn load-catalog
  "Read only the AST-extracted base handlers; optional providers remain absent."
  []
  (edn/read-string (slurp (io/resource "hive_blender/catalog.edn"))))

(m/=> load-catalog [:=> [:cat] [:vector [:map [:id :string]]]])
