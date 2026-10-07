(ns hive-blender.core
  "Portable command projection and reply classification; values only.")

(defn commands
  "Project the base command ids from catalog entries."
  [entries]
  (mapv :id entries))

(defn command
  "Build a base command or return a typed refusal; code requires confirmation."
  [entries id params confirm]
  (cond
    (not (some #(= id (:id %)) entries)) {:error {:kind :blender/unknown-command :hint "Command is not in the base catalog."}}
    (contains? #{"get_telemetry_consent" "set_telemetry_consent"
                 "get_polyhaven_status" "get_hyper3d_status" "get_sketchfab_status"
                 "get_polypizza_status" "get_hunyuan3d_status" "get_tripo_status"}
               id)
    {:error {:kind :blender/forbidden-command :hint "Telemetry, Premium and provider status commands are disabled."}}
    (not (map? params)) {:error {:kind :blender/invalid-params :hint "Params must be a map."}}
    (and (= id "execute_code") (not (true? confirm))) {:error {:kind :blender/confirmation-required :hint "Set :confirm true for execute_code."}}
    :else {:ok {"type" id "params" params}}))

(defn reply
  "Classify a parsed add-on reply, including errors nested inside success results."
  [value]
  (let [status (get value "status")
        result (get value "result")]
    (cond
      (and (= status "success") (map? result) (or (contains? result "error") (contains? result :error)))
      {:error {:kind :blender/result-error :hint (str (or (get result "error") (:error result)))}}
      (= status "success") {:ok result}
      (= status "error") {:error {:kind :blender/reply-error :hint (str (get value "message"))}}
      :else {:error {:kind :blender/invalid-reply :hint "Expected a Blender success or error envelope."}})))
