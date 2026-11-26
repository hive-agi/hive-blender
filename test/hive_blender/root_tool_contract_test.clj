(ns hive-blender.root-tool-contract-test
  (:require [hive-addon.tool-contract.test :refer [deftest-root-tools]]
            [hive-addon.protocol :as proto]
            [hive-blender.addon :as a]
            [hive-blender.stub :as stub]))

(deftest-root-tools root-tools
  (let [instance (a/addon-ctor {:link (stub/stub-link {}) :code-gate (stub/gate 16)})]
    (proto/initialize! instance {})
    (proto/tools instance)))
