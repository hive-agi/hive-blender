(ns hive-blender.catalog
  "Read the extracted unconditional add-on command catalog."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]))

(defn load-catalog
  "Read the AST-extracted base handlers, omitting disabled provider, Premium and telemetry commands."
  []
  (filterv (fn [{:keys [id]}]
             (not (contains? #{"get_telemetry_consent" "set_telemetry_consent"
                               "get_polyhaven_status" "get_hyper3d_status" "get_sketchfab_status"
                               "get_polypizza_status" "get_hunyuan3d_status" "get_tripo_status"}
                             id)))
           (edn/read-string (slurp (io/resource "hive_blender/catalog.edn")))))

(m/=> load-catalog [:=> [:cat] [:vector [:map [:id :string]]]])
