(ns hive-blender.stl
  "STL values, Blender snippets and binary inspection."
  (:require [clojure.data.json :as json] [clojure.java.io :as io]
            [clojure.string :as str] [malli.core :as m]
            [hive-schemas.schema :as schemas])
  (:import (java.nio ByteBuffer ByteOrder)
           (java.nio.file Files LinkOption)
           (java.security MessageDigest) (java.util HexFormat)))

(def ExportStlRequest
  [:map {:closed true} [:path :string]
   [:objects [:or [:enum :selected :all] [:vector {:min 1} :string]]]
   [:scale {:optional true} [:and number? [:fn #(and (Double/isFinite (double %)) (< 0 %) (<= % 1000))]]]
   [:target-mm {:optional true} [:and number? [:fn #(and (Double/isFinite (double %)) (pos? %))]]]])

(def ModelArtifact
  [:map [:path :string] [:format [:= :stl]] [:sha256 :string] [:bytes pos-int?]
   [:units [:= :mm]] [:mm number?] [:bbox-mm [:tuple number? number? number?]]
   [:provenance [:map [:source [:= :blender]] [:objects [:vector :string]]
                 [:scale number?] [:blender-version :string]]]])

(schemas/register-all! {:hive.blender/ExportStlRequest ExportStlRequest
                        :hive.blender/ModelArtifact ModelArtifact})

(defn valid-request?
  "Accept an unused nonsymlink STL path under user.home and one sizing method."
  [request]
  (and (m/validate ExportStlRequest request)
       (not (and (contains? request :scale) (contains? request :target-mm)))
       (every? #(and (not (str/blank? %)) (not (str/includes? % "\n")))
               (if (vector? (:objects request)) (:objects request) []))
       (let [path (io/file (:path request))
             home (.toRealPath (.toPath (io/file (System/getProperty "user.home"))) (make-array LinkOption 0))
             parent (.getParent (.toPath path))]
         (and (.isAbsolute path) (str/ends-with? (str/lower-case (:path request)) ".stl")
              parent (Files/isDirectory parent (make-array LinkOption 0))
              (.startsWith (.toRealPath parent (make-array LinkOption 0)) home)
              (not (Files/exists (.toPath path) (make-array LinkOption 0)))))))

(defn script
  "Build a Blender snippet from a validated request and positive millimetre scale."
  [request scale query?]
  (let [names (if (vector? (:objects request)) (:objects request) [])
        python-str #(str/replace (json/write-str %) "\\/" "/")
        selector (case (:objects request) :selected "o.select_get()" :all "True"
                       (str "any(o.name == n or o.name.startswith(n) for n in " (python-str names) ")"))]
    (str "import bpy\nimport json\nfrom mathutils import Vector\n"
         "_objects = [o for o in bpy.context.scene.objects if o.type == 'MESH' and " selector "]\n"
         "if not _objects: raise ValueError('No mesh objects selected')\n"
         "_points = [o.matrix_world @ Vector(c) for o in _objects for c in o.bound_box]\n"
         "_dims = [max(p[i] for p in _points) - min(p[i] for p in _points) for i in range(3)]\n"
         (if query?
           "print('HIVE_BBOX:' + json.dumps(_dims))\n"
           (str "import pathlib\n"
                "if pathlib.Path(" (python-str (:path request)) ").exists(): raise FileExistsError('Destination exists')\n"
                "_selected = list(bpy.context.selected_objects)\n"
                "try:\n    bpy.ops.object.select_all(action='DESELECT')\n"
                "    for o in _objects: o.select_set(True)\n"
                "    if bpy.app.version >= (4, 0, 0):\n"
                "        bpy.ops.wm.stl_export(filepath=" (python-str (:path request))
                ", export_selected_objects=True, apply_modifiers=True, global_scale=" (double scale) ")\n"
                "    else:\n        bpy.ops.export_mesh.stl(filepath=" (python-str (:path request))
                ", use_selection=True, use_mesh_modifiers=True, global_scale=" (double scale) ")\n"
                "finally:\n    bpy.ops.object.select_all(action='DESELECT')\n"
                "    for o in _selected: o.select_set(True)\n"
                "print('HIVE_EXPORT:' + json.dumps({'objects': [o.name for o in _objects], 'version': bpy.app.version_string}))\n")))))

(defn parse-bbox
  "Read three finite nonnegative Blender dimension values from a query receipt."
  [output]
  (when (string? output)
    (when-let [line (some #(when (str/starts-with? % "HIVE_BBOX:") %) (str/split-lines output))]
      (try
        (let [dims (mapv double (json/read-str (subs line (count "HIVE_BBOX:"))))]
          (when (and (= 3 (count dims))
                     (every? #(and (Double/isFinite %) (<= 0 %)) dims)) dims))
        (catch Exception _ nil)))))

(defn inspect
  "Validate binary STL triangles, finite floats and length; return bytes, digest and bbox."
  [path]
  (let [file (.toPath (io/file path))]
    (when-not (Files/isRegularFile file (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (throw (ex-info "Not a regular STL file" {})))
    (let [size (Files/size file)]
      (when-not (<= 84 size (* 256 1024 1024)) (throw (ex-info "STL size outside bounds" {})))
      (let [bytes (Files/readAllBytes file)
            buffer (doto (ByteBuffer/wrap bytes) (.order ByteOrder/LITTLE_ENDIAN))
            triangles (Integer/toUnsignedLong (.getInt buffer 80))]
        (when (or (zero? triangles) (not= size (+ 84 (* 50 triangles))))
          (throw (ex-info "STL triangle count disagrees with file length" {})))
        (let [lo (double-array [Double/POSITIVE_INFINITY Double/POSITIVE_INFINITY Double/POSITIVE_INFINITY])
              hi (double-array [Double/NEGATIVE_INFINITY Double/NEGATIVE_INFINITY Double/NEGATIVE_INFINITY])]
          (dotimes [t triangles]
            (let [base (+ 84 (* 50 t))]
              (dotimes [v 4]
                (dotimes [axis 3]
                  (let [x (.getFloat buffer (+ base (* 12 v) (* axis 4)))]
                    (when-not (Float/isFinite x) (throw (ex-info "Nonfinite STL coordinate" {})))
                    (when (pos? v)
                      (aset lo axis (min (aget lo axis) x))
                      (aset hi axis (max (aget hi axis) x))))))))
          (let [digest (.digest (doto (MessageDigest/getInstance "SHA-256") (.update bytes)))]
            {:bytes size :sha256 (.formatHex (HexFormat/of) digest)
             :bbox-mm (mapv #(- (aget hi %) (aget lo %)) (range 3))}))))))

(m/=> valid-request? [:=> [:cat :any] :boolean])
(m/=> script [:=> [:cat ExportStlRequest number? :boolean] :string])
(m/=> parse-bbox [:=> [:cat :any] [:maybe [:tuple number? number? number?]]])
(m/=> inspect [:=> [:cat :string] :map])
