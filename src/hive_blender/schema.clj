(ns hive-blender.schema
  "Boundary value objects for the Blender add-on protocol."
  (:require [malli.core :as m]))

(def Command [:map ["type" :string] ["params" :map]])
(def Reply [:orn [:success [:map ["status" [:= "success"]] ["result" :any]]]
            [:failure [:map ["status" [:= "error"]] ["message" :string]]]])
(def SceneRef [:map [:file :string] [:scene :string]])
(def ObjectRef [:map [:file :string] [:scene :string] [:name :string]])
(def ExportRequest [:map [:path :string] [:format [:enum :glb :fbx]]])
(def AddonInfo [:map [:protocol-version [:= 13]]])
(def Outcome [:orn [:ok [:map [:ok :any]]] [:error [:map [:error [:map [:kind :keyword] [:hint :string]]]]]])
