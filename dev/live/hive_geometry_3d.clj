(ns hive-geometry-3d
  (:require [hive-blender.transport.socket :as socket]
            [hive-blender.catalog :as catalog]
            [hive-blender.service :as service]
            [hive-blender.port :as port]))

(def output "/home/klein/.cache/hive-craft-blender/out/")
(def link (socket/socket-link {:timeout-ms 60000 :max-reply-bytes 4194304}))
(def entries (catalog/load-catalog))
;; Installed per-session CodeGate: deny external I/O and every optional service.
(def gate (reify port/CodeGate
            (allow-code? [_ code]
              (and (string? code)
                   (<= (count (.getBytes code "UTF-8")) 200000)
                   (not (re-find #"(?i)import os|subprocess|requests|urllib|premium|telemetry|polyhaven|sketchfab|hyper3d|tripo|open\(" code))))))
(def calls (atom []))
(defn send! [id params confirm]
  (let [started (System/nanoTime)
        result (service/call entries link gate id params confirm)]
    (swap! calls conj {:id id :ms (long (/ (- (System/nanoTime) started) 1000000)) :result result})
    (when (:error result) (throw (ex-info (str "Blender " id ": " (:error result)) {:result result})))
    result))
(defn code! [source] (send! "execute_code" {"code" source} true))
(defn shot! [n] (send! "get_viewport_screenshot" {"filepath" (str output "stage-" n ".png") "max_size" 1400} false))
(def golden-angle (* Math/PI (/ 137.5 180.0)))
(defn cell [q r]
  (let [x (* 1.19 (+ q (/ r 2.0)))
        y (* 1.03057 r)
        theta (Math/atan2 y x)
        radius (Math/sqrt (+ (* x x) (* y y)))
        phase (- theta (* radius golden-angle))
        height (+ 0.24 (* 0.76 (Math/exp (* -0.13 radius))) (* 0.31 (+ 1 (Math/cos (* 2.2 phase)))))]
    [q r x y height]))
(def cells (vec (for [q (range -5 6) r (range -5 6) :when (<= (max (abs q) (abs r) (abs (+ q r))) 5)] (cell q r))))
(defn py-cell [[q r x y h]] (format "(%d,%d,%.5f,%.5f,%.5f)" q r x y h))
(defn stage1 []
  (send! "get_scene_info" {} false)
  (code! "import bpy\nfor obj in list(bpy.data.objects): bpy.data.objects.remove(obj, do_unlink=True)\nprint('cleared')")
  ;; Short batches: Blender redraws between requests so every ring visibly arrives.
  (doseq [ring (range 6)]
    (let [batch (filter (fn [[q r]] (= ring (max (abs q) (abs r) (abs (+ q r))))) cells)]
      (code! (str "import bpy, math\nfor q,r,x,y,h in [" (clojure.string/join "," (map py-cell batch)) "]:\n"
                  " bpy.ops.mesh.primitive_cylinder_add(vertices=6, radius=0.65, depth=h, location=(x,y,h/2))\n"
                  " o=bpy.context.object; o.name=f'Slab_{q}_{r}'; o.rotation_euler.z=math.pi/6\n"
                  " mod=o.modifiers.new('Soft carved edge','BEVEL'); mod.width=0.055; mod.segments=2\n"
                  " o.modifiers.new('Weighted normals','WEIGHTED_NORMAL')\n"
                  "print('ring " ring "'," (count batch) ")")))
      (Thread/sleep 320)))
  (shot! 1))
(defn stage2 []
  (code! "import bpy\nhoney=bpy.data.materials.new('Amber honey | translucent gold'); honey.diffuse_color=(0.92,0.39,0.045,1); honey.use_nodes=True\np=honey.node_tree.nodes.get('Principled BSDF'); p.inputs['Base Color'].default_value=(0.82,0.31,0.018,1); p.inputs['Metallic'].default_value=0.23; p.inputs['Roughness'].default_value=0.24; p.inputs['Transmission Weight'].default_value=0.18\nfor o in bpy.data.objects:\n if o.name.startswith('Slab_'): o.data.materials.append(honey)\ndark=bpy.data.materials.new('Midnight basalt'); dark.diffuse_color=(0.012,0.021,0.031,1); dark.use_nodes=True; dark.node_tree.nodes.get('Principled BSDF').inputs['Base Color'].default_value=(0.012,0.021,0.031,1)\nbpy.ops.mesh.primitive_cylinder_add(vertices=6, radius=7.2, depth=0.22, location=(0,0,-0.23)); o=bpy.context.object; o.name='Midnight ground'; o.data.materials.append(dark)\nprint('amber + basalt')")
  (shot! 2))
(defn stage3 []
  (code! "import bpy, math\nringmat=bpy.data.materials.new('Flower | luminous warm ivory'); ringmat.diffuse_color=(1,0.7,0.28,1); ringmat.use_nodes=True; bs=ringmat.node_tree.nodes.get('Principled BSDF'); bs.inputs['Base Color'].default_value=(1,0.56,0.16,1); bs.inputs['Emission Color'].default_value=(1,0.35,0.06,1); bs.inputs['Emission Strength'].default_value=0.7\nfor i,(cx,cy) in enumerate([(0,0)]+[(1.65*math.cos(i*math.pi/3),1.65*math.sin(i*math.pi/3)) for i in range(6)]):\n bpy.ops.mesh.primitive_torus_add(major_radius=1.65, minor_radius=0.018, major_segments=96, minor_segments=8, location=(cx,cy,1.56)); o=bpy.context.object; o.name=f'Flower of Life {i}'; o.data.materials.append(ringmat)\nbpy.ops.mesh.primitive_cylinder_add(vertices=6, radius=0.58, depth=0.13, location=(0,0,1.55)); sun=bpy.context.object; sun.name='Solar hexagon | the eye'; sun.rotation_euler.z=math.pi/6; sun.data.materials.append(ringmat)\np=ringmat.node_tree.nodes.get('Principled BSDF'); print('flower of life and solar eye')")
  (shot! 3))
(defn stage4 []
  (code! "import bpy, math\nfrom mathutils import Vector\nbpy.ops.object.camera_add(location=(10,-13,13)); cam=bpy.context.object; cam.name='Golden perspective'; direction=Vector((0,0,0.35))-cam.location; cam.rotation_euler=direction.to_track_quat('-Z','Y').to_euler(); cam.data.type='ORTHO'; cam.data.ortho_scale=17.5; bpy.context.scene.camera=cam\nbpy.ops.object.light_add(type='AREA', location=(-3,-4,9)); light=bpy.context.object; light.name='Honeybox soft light'; light.data.energy=1900; light.data.shape='DISK'; light.data.size=9\nscene=bpy.context.scene; scene.render.engine='CYCLES'; scene.cycles.samples=32; scene.render.resolution_x=1400; scene.render.resolution_y=1100; scene.render.resolution_percentage=100\nfor screen in bpy.data.screens:\n for area in screen.areas:\n  if area.type=='VIEW_3D':\n   space=area.spaces.active; space.region_3d.view_perspective='CAMERA'; space.shading.type='MATERIAL'\nprint('camera, light, material preview')")
  (shot! 4))
(defn stage5 []
  (send! "export_scene" {"filepath" (str output "hive-geometry.glb") "format" "glb"} false)
  (code! (str "import bpy\nfor o in bpy.context.selected_objects: o.select_set(False)\n"
              "slabs=[o for o in bpy.context.scene.objects if o.name.startswith('Slab_')]\n"
              "for o in slabs: o.select_set(True)\n"
              "bpy.context.view_layer.objects.active=slabs[0]\n"
              "bpy.ops.wm.stl_export(filepath='" output "hive-geometry-slab.stl', export_selected_objects=True, apply_modifiers=True)\n"
              "for o in slabs: o.select_set(False)\n"
              "print('STL slab meshes:',len(slabs))"))
  (shot! 5))
(defn run! [] (stage1) (stage2) (stage3) (stage4) (stage5) {:calls (count @calls) :ms (reduce + (map :ms @calls))}))
