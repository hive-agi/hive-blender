(ns portability
  "Eighty deterministic codec/catalog projection checks across four runtimes."
  (:require [hive-blender.core :as core]))

(def entries [{:id "get_scene_info"} {:id "execute_code"}])

(defn check
  "Return the number of passing and total portable scenarios."
  []
  (let [cases (for [n (range 80)]
                (let [id (if (even? n) "get_scene_info" "unknown")
                      request (core/command entries id {:n n} false)]
                  (and (= ["get_scene_info" "execute_code"] (core/commands entries))
                       (= (even? n) (contains? request :ok))
                       (= (odd? n) (contains? request :error))
                       (= {:error {:kind :blender/confirmation-required
                                   :hint "Set :confirm true for execute_code."}}
                          (core/command entries "execute_code" {} false))
                       (= {:ok n} (core/reply {"status" "success" "result" n}))
                       (= :blender/result-error
                          (get-in (core/reply {"status" "success" "result" {"error" "bad"}})
                                  [:error :kind])))))]
    {:passes (count (filter identity cases)) :total (count cases)}))

(defn -main [& _]
  (let [result (check)]
    (println result)
    (when (not= (:passes result) (:total result))
      (throw (ex-info "Blender portability failed" result)))))

#?(:cljs (set! *main-cli-fn* -main))
