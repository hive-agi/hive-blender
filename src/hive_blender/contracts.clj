(ns hive-blender.contracts
  "JVM contracts for the portable core."
  (:require [malli.core :as m]
            [hive-blender.core]
            [hive-blender.schema :as schema]))

(m/=> hive-blender.core/commands [:=> [:cat [:sequential :map]] [:vector :string]])
(m/=> hive-blender.core/command [:=> [:cat [:sequential :map] :string :any :boolean] schema/Outcome])
(m/=> hive-blender.core/reply [:=> [:cat :any] schema/Outcome])
