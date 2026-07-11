(ns kotoba.amenominaka.ui
  "M5 (ADR-2607100100): the interactive UI shell — real environment-preset
  controls (weather/terrain/postfx/vegetation) and an orbit/zoom camera
  over the WebGPU viewport, wired to M0-M2's already-landed pipeline
  (previously only reachable via a static single-frame demo).

  M8: a second camera mode, free-fly (WASD movement + mouse-look), toggled
  alongside orbit. Needed no new `kami.webgpu.ir`/`kami.webgpu` API at all
  — `rig->camera` was always just one way to produce `:eye`/`:target`;
  [[fly-eye-target]] below computes them directly from a
  position+yaw+pitch state the same way, and `apply-camera!` only ever
  cared about the resulting `:eye`/`:target` map, not which mode produced
  it — so M4's instance-buffer cache stays hot in fly mode exactly like
  it does in orbit mode (camera-only frames never touch `:instances`).

  M9: `omni.timeline` parity (D7's own scoping — \"minimal keyframe/camera-
  path\", see `kotoba.amenominaka.timeline`'s docstring). \"Record
  Keyframe\" captures wherever the camera currently is (orbit OR fly, both
  just produce an :eye/:target the same way M8 already established) into
  an ordered path; \"Play\" drives a real requestAnimationFrame loop that
  interpolates along it and overrides the render-IR's camera each frame —
  a third, temporary camera source layered over orbit/fly (see
  [[apply-timeline-at!]]), not a third `:camera-mode` value, since
  playback is a transient override, not a persistent mode the user
  toggles the same way.

  Built on the org's DEFAULT UI/UX design system per ADR-2607022800:
  `shitsuke` (structure) + `liquid-glass-ui` (material) + `kotoba-ui`
  (single require-point) + `appkit` (desktop binding — this is a panels/
  toolbar/sliders desktop-style app, not touch/card-first, so `appkit`
  over `uikit`). This is the FIRST consumer wiring that design system to
  a WebGPU-canvas-centric app (confirmed via repo-wide grep before
  starting) — but the pattern itself (DOM chrome siblings around a
  `<canvas>` leaf node) is precedented by `kami-engine-hud`, and
  shitsuke/kotoba-ui are DOM-only by design (`liquid-glass.gpu`, true
  in-canvas glass rendering, is explicitly future work upstream) which is
  exactly the shape this app needs, not a mismatch.

  `kotoba-ui`'s `menu-select`/`button` are uncontrolled-in-React (real,
  confirmed bugs — a `<select>`/`<option>` with no `:value`/`:key` React
  props, and `button` has no `:on-click` at all, only shitsuke's SSR
  `:act` contract). [[preset-select]]/[[btn]] below are small hand-rolled
  controlled replacements with the SAME DOM shape (so the generated CSS
  still targets them correctly) — this exact workaround, including the
  DOM-shape-preservation reasoning, is copied from
  `murakumo-studio/src/murakumo_studio/ui.cljs`'s real, live-reproduced
  fix for the identical bug."
  (:require [cljs.reader :as reader]
            [reagent.core :as r]
            [reagent.dom.client :as rdomc]
            [appkit.core :as shape]
            [kotoba-ui.core :as ui]
            [bim]
            [kotoba.amenominaka.scene :as amenominaka-scene]
            [kotoba.amenominaka.render-ir :as render-ir]
            [kotoba.amenominaka.usd-export :as usd-export]
            [kotoba.amenominaka.gltf-export :as gltf-export]
            [kotoba.amenominaka.timeline :as tl]
            [kami.webgpu :as webgpu]
            [kami.webgpu.ir :as ir]))

;; ── controlled-component workarounds (see ns docstring) ──

(defn- btn
  ([label on-click] (btn label on-click nil))
  ([label on-click id]
   [:button {:id id :class (ui/class-name :button) :type "button" :on-click on-click} label]))

(defn- preset-select [options {:keys [id value on-change]}]
  [:div {:class (ui/class-name :menu-select)}
   [:select {:id id :value (or value "") :on-change on-change}
    (for [[v l] options] ^{:key v} [:option {:value v} l])]
   [:span {:aria-hidden true :class (ui/class-name :specular)}]])

;; ── the sample building (same fixture M0-M4's own tests/demos use) ──

(defn- sample-building []
  (-> (bim/project "Quarry Walk Lodge")
      (update :sites conj
              (bim/site
               {:id 1 :name "Site 1" :geo nil :placement :identity
                :buildings
                [(bim/building
                  {:id 2 :name "Lodge" :placement :identity :reference-elevation 0.0
                   :storeys
                   [(bim/storey
                     {:id 3 :name "L1" :elevation 0.0 :height 3.5 :placement :identity
                      :spaces []
                      :elements
                      [(bim/element
                        {:id 4 :kind :wall :name "Wall 1" :global-id "GUID-0004"
                         :placement :identity
                         :geometry (bim/axis-sweep-geometry
                                    [[0.0 0.0 0.0] [10.0 0.0 0.0]]
                                    (bim/rectangle-profile 0.2 3.5))})]})]})]}))))

;; ── real preset ids, per each domain's own shipped EDN (verified by
;; reading kami-atmosphere-scene/kami-terrain-scene/kami-vegetation-scene/
;; kami-postfx-scene's own `*-edn` source, not guessed) ──

(def weather-options [["overcast" "Overcast"] ["clear" "Clear"]])
(def terrain-options [["plains" "Plains"] ["quarry" "Quarry"] ["desert" "Desert"] ["tundra" "Tundra"]])
(def postfx-options [["nintendo" "Nintendo"] ["retro" "Retro"]
                      ["final-fantasy" "Final Fantasy"] ["baminiku-character" "Baminiku Character"]])
(def vegetation-options [["grass" "Grass"] ["fern" "Fern"] ["palm" "Palm"]
                          ["conifer" "Conifer"] ["bush" "Bush"] ["cactus" "Cactus"] ["moss" "Moss"]])

;; ── state: plain reagent atom + swap! (the real pattern in every actual
;; kotoba-ui/appkit consumer found — shitsuke.re-frame.core exists as a
;; portable seam but has zero real consumers) ──

(defonce state
  (r/atom {:weather "overcast" :terrain "plains" :postfx "nintendo" :vegetation ["grass"]
           :building (sample-building) :selected-element 4 :next-element-id 5
           :building-history [] :building-future []
           :camera-mode :orbit
           :camera {:azimuth 0.785 :distance 64.0 :height 55.0}
           :fly {:pos [0.0 5.0 0.0] :yaw 0.0 :pitch 0.0}
           :fly-keys #{}
           :timeline [] :playhead 0.0 :playing? false
           :pivot nil
           :render-ir nil
           :webgpu-ctx nil
           :dragging? false :last-x 0 :last-y 0}))

;; ── scene recompute + camera application ──
;;
;; apply-camera! only assoc-in's :eye/:target into the ALREADY-COMPUTED
;; render-ir — it never touches :instances, so kotoba-lang/webgpu's M4
;; instance-buffer cache (ADR-2607100100, ~30-40x at scale) stays hot
;; across every camera-only frame (drag/wheel); only a real preset change
;; (recompute-scene!) rebuilds :instances and pays a fresh upload.

(defn- fly-eye-target
  "Free-fly `:eye`/`:target` from a `{:pos [x y z] :yaw :pitch}` state —
  the same job `ir/rig->camera` does for orbit, just a different (and
  simpler) mapping: `:eye` is `:pos` directly, `:target` is one unit ahead
  along the look direction (yaw about Y, pitch about the local right
  axis). Only the direction matters for a look-at target, so the fixed
  1-unit distance is arbitrary."
  [{:keys [pos yaw pitch]}]
  (let [[px py pz] pos
        cp (js/Math.cos pitch) sp (js/Math.sin pitch)
        sy (js/Math.sin yaw) cy (js/Math.cos yaw)]
    {:eye pos
     :target [(+ px (* cp sy)) (+ py sp) (+ pz (* cp cy))]}))

(defn- apply-camera! []
  (let [{:keys [render-ir camera camera-mode pivot fly webgpu-ctx]} @state]
    (when (and render-ir (or pivot (= camera-mode :fly)))
      (let [{:keys [eye target]} (if (= camera-mode :fly)
                                    (fly-eye-target fly)
                                    (ir/rig->camera camera pivot))
            ir' (-> render-ir (assoc-in [:globals :eye] eye) (assoc-in [:globals :target] target))]
        (swap! state assoc :render-ir ir')
        (when webgpu-ctx (webgpu/draw! webgpu-ctx ir'))))))

(defn- current-scene []
  (let [{:keys [weather terrain postfx vegetation building]} @state]
    (amenominaka-scene/compose {:building building
                                 :weather weather :terrain terrain :postfx postfx
                                 :vegetation vegetation})))

(defn- recompute-scene! []
  (let [base-ir (render-ir/scene->render-ir (current-scene))
        target (get-in base-ir [:globals :target])
        pivot [(nth target 0) (nth target 2)]]
    (swap! state assoc :render-ir base-ir :pivot pivot)
    (apply-camera!)))

;; ── semantic scene editing ──

(defn- current-storey [] (bim/find-storey (:building @state) 3))
(defn- scene-elements [] (:elements (current-storey)))
(defn- selected-element []
  (first (filter #(= (:selected-element @state) (:id %)) (scene-elements))))
(defn- commit-building! [building]
  (swap! state (fn [s] (-> s
                            (update :building-history conj (:building s))
                            (assoc :building building :building-future []))))
  (recompute-scene!))
(defn- add-wall! []
  (let [id (:next-element-id @state) y (* 1.5 (- id 4))
        wall (bim/wall {:id id :name (str "Wall " id) :start [0 y 0] :end [8 y 0]
                        :thickness 0.2 :height 3.5 :material "Concrete"})]
    (swap! state assoc :selected-element id)
    (swap! state update :next-element-id inc)
    (commit-building! (bim/add-element (:building @state) 3 wall))))
(defn- delete-selected! []
  (when-let [e (selected-element)]
    (commit-building! (bim/delete-element (:building @state) 3 (:id e)))
    (swap! state assoc :selected-element (some-> (first (scene-elements)) :id))))
(defn- update-selected! [field value]
  (when-let [e (selected-element)]
    (let [[[x y z] [x1 _ _]] (get-in e [:geometry :axis] [[0 0 0] [8 0 0]])
          length (if (= field :length) value (- x1 x))
          height (if (= field :height) value (get-in e [:geometry :profile :height] 3.5))
          thickness (if (= field :thickness) value (get-in e [:geometry :profile :thickness] 0.2))
          name (if (= field :name) value (:name e))
          updated (bim/wall {:id (:id e) :name name :start [x y z] :end [(+ x length) y z]
                             :height height :thickness thickness :material "Concrete"})]
      (commit-building! (bim/update-element (:building @state) 3 (:id e) (constantly updated))))))
(defn- undo-building! []
  (when-let [previous (peek (:building-history @state))]
    (swap! state (fn [s] (assoc s :building previous
                                :building-history (pop (:building-history s))
                                :building-future (conj (:building-future s) (:building s)))))
    (recompute-scene!)))
(defn- redo-building! []
  (when-let [next (peek (:building-future @state))]
    (swap! state (fn [s] (assoc s :building next
                                :building-future (pop (:building-future s))
                                :building-history (conj (:building-history s) (:building s)))))
    (recompute-scene!)))
(defn- save-project! []
  (.setItem js/localStorage "amenominaka.project" (pr-str (:building @state))))
(defn- load-project! []
  (when-let [stored (.getItem js/localStorage "amenominaka.project")]
    (let [building (reader/read-string stored)]
      (when (amenominaka-scene/bim-project? building) (commit-building! building)))))

;; ── mouse/wheel orbit camera (M2's own gap: "interactive orbit/fly
;; camera controls are not implemented" — closed here (orbit), extended
;; to a second free-fly mode in M8) ──

(defn- on-mouse-down [e]
  (swap! state assoc :dragging? true :last-x (.-clientX e) :last-y (.-clientY e)))

(defn- on-mouse-move [e]
  (when (:dragging? @state)
    (let [{:keys [last-x last-y camera-mode]} @state
          dx (- (.-clientX e) last-x)
          dy (- (.-clientY e) last-y)]
      (swap! state assoc :last-x (.-clientX e) :last-y (.-clientY e))
      (if (= camera-mode :fly)
        ;; mouse-look: drag right -> look right (yaw+), drag up -> look up
        ;; (pitch+, screen Y grows downward so this is a subtraction).
        ;; Pitch clamped short of ±90° to avoid a gimbal flip through the pole.
        (swap! state update :fly
               (fn [f] (-> f
                           (update :yaw + (* dx 0.005))
                           (update :pitch #(-> % (- (* dy 0.005)) (max -1.5) (min 1.5))))))
        (swap! state update :camera
               (fn [c] (-> c
                           (update :azimuth + (* dx 0.01))
                           (update :height #(max 5.0 (- % (* dy 0.5))))))))
      (apply-camera!))))

(defn- on-mouse-up [_e] (swap! state assoc :dragging? false))

(defn- on-wheel [e]
  (.preventDefault e)
  (when (= (:camera-mode @state) :orbit)
    (swap! state update :camera update :distance
           (fn [d] (max 10.0 (min 400.0 (+ d (* (.-deltaY e) 0.1))))))
    (apply-camera!)))

;; ── free-fly keyboard movement (M8) ──
;;
;; WASD moves relative to the current look direction, projected onto the
;; horizontal plane (yaw only — ignoring pitch, so looking down while
;; pressing W walks forward instead of flying into the ground, standard
;; FPS-camera behavior); Space/Shift move straight up/down. A real
;; requestAnimationFrame loop (not per-keydown deltas) so movement is
;; smooth and framerate-independent while a key is held — started when
;; entering fly mode, self-terminating (checks :camera-mode each tick)
;; when leaving it.

(def ^:private fly-speed 12.0) ;; world units / second

(def ^:private key->action
  {"w" :forward "s" :backward "a" :left "d" :right
   " " :up "shift" :down})

(defn- on-keydown [e]
  (when (= (:camera-mode @state) :fly)
    (when-let [action (key->action (.toLowerCase (.-key e)))]
      (.preventDefault e)
      (swap! state update :fly-keys conj action))))

(defn- on-keyup [e]
  (when-let [action (key->action (.toLowerCase (.-key e)))]
    (swap! state update :fly-keys disj action)))

(defn- move-fly! [dt]
  (let [{:keys [fly fly-keys]} @state
        {:keys [yaw]} fly
        held (fn [a] (contains? fly-keys a))
        forward [(js/Math.sin yaw) 0.0 (js/Math.cos yaw)]
        right [(js/Math.cos yaw) 0.0 (- (js/Math.sin yaw))]
        move-x (+ (if (held :right) 1.0 0.0) (if (held :left) -1.0 0.0))
        move-z (+ (if (held :forward) 1.0 0.0) (if (held :backward) -1.0 0.0))
        move-y (+ (if (held :up) 1.0 0.0) (if (held :down) -1.0 0.0))
        step (* fly-speed dt)]
    (when-not (zero? (+ (js/Math.abs move-x) (js/Math.abs move-z) (js/Math.abs move-y)))
      (swap! state update-in [:fly :pos]
             (fn [[px py pz]]
               [(+ px (* step (+ (* move-x (nth right 0)) (* move-z (nth forward 0)))))
                (+ py (* step move-y))
                (+ pz (* step (+ (* move-x (nth right 2)) (* move-z (nth forward 2)))))]))
      (apply-camera!))))

(defonce fly-loop-running? (atom false))

(defn- fly-loop! [last-t]
  (when (= (:camera-mode @state) :fly)
    (let [now (js/performance.now)]
      (move-fly! (/ (- now last-t) 1000.0))
      (js/requestAnimationFrame (fn [] (fly-loop! now)))))
  (when-not (= (:camera-mode @state) :fly) (reset! fly-loop-running? false)))

(defn- start-fly-loop! []
  (when-not @fly-loop-running?
    (reset! fly-loop-running? true)
    (js/requestAnimationFrame (fn [] (fly-loop! (js/performance.now))))))

(defn- toggle-camera-mode! []
  (let [{:keys [camera-mode render-ir]} @state]
    (if (= camera-mode :fly)
      (swap! state assoc :camera-mode :orbit)
      (do
        ;; hand off smoothly: seed fly's pos/yaw from wherever orbit's
        ;; eye/target currently are, so toggling mid-orbit doesn't jump.
        (let [eye (get-in render-ir [:globals :eye] [0.0 5.0 0.0])
              target (get-in render-ir [:globals :target] [0.0 0.0 0.0])
              [ex ey ez] eye [tx ty tz] target
              dx (- tx ex) dz (- tz ez)
              yaw (js/Math.atan2 dx dz)
              horiz (js/Math.sqrt (+ (* dx dx) (* dz dz)))
              pitch (js/Math.atan2 (- ty ey) (max 1e-6 horiz))]
          (swap! state assoc :camera-mode :fly
                 :fly {:pos eye :yaw yaw :pitch (-> pitch (max -1.5) (min 1.5))}))
        (start-fly-loop!)))
    (apply-camera!)))

;; ── omni.timeline parity: minimal keyframe/camera-path (M9) ──
;;
;; apply-timeline-at! pushes eye/target straight into the render-IR the
;; same way apply-camera! does — it deliberately does NOT go through
;; apply-camera!/camera-mode, since playback is a transient override of
;; whatever the camera is currently doing, not a mode switch (orbit/fly
;; state is left untouched underneath and resumes as-is once playback/
;; scrubbing stops).

(defn- apply-timeline-at! [t]
  (let [{:keys [render-ir timeline webgpu-ctx]} @state]
    (when-let [{:keys [eye target]} (tl/eval-at timeline t)]
      (let [ir' (-> render-ir (assoc-in [:globals :eye] eye) (assoc-in [:globals :target] target))]
        (swap! state assoc :render-ir ir' :playhead t)
        (when webgpu-ctx (webgpu/draw! webgpu-ctx ir'))))))

(defn- record-keyframe! []
  (let [{:keys [render-ir]} @state
        eye (get-in render-ir [:globals :eye])
        target (get-in render-ir [:globals :target])]
    (when (and eye target)
      (swap! state update :timeline tl/add-keyframe eye target))))

(defn- clear-timeline! []
  (swap! state assoc :timeline [] :playhead 0.0 :playing? false))

(defonce timeline-loop-running? (atom false))

(defn- timeline-loop! [last-t]
  (let [{:keys [playing? playhead timeline]} @state
        dur (tl/duration timeline)]
    (if (and playing? (< playhead dur))
      (let [now (js/performance.now)
            t' (min dur (+ playhead (/ (- now last-t) 1000.0)))]
        (apply-timeline-at! t')
        (js/requestAnimationFrame (fn [] (timeline-loop! now))))
      (do (swap! state assoc :playing? false)
          (reset! timeline-loop-running? false)))))

(defn- play-timeline! []
  (let [{:keys [timeline playhead]} @state]
    (when (and (seq timeline) (not @timeline-loop-running?))
      ;; restart from the top if already at (or past) the end
      (when (>= playhead (tl/duration timeline)) (swap! state assoc :playhead 0.0))
      (swap! state assoc :playing? true)
      (reset! timeline-loop-running? true)
      (js/requestAnimationFrame (fn [] (timeline-loop! (js/performance.now)))))))

(defn- pause-timeline! [] (swap! state assoc :playing? false))

(defn- scrub-timeline! [t] (apply-timeline-at! t))

;; ── USD export (wires M1's kotoba.amenominaka.usd-export to a real
;; browser download, using the CURRENTLY selected presets) ──

(defn- download-blob! [blob filename]
  (let [url (js/URL.createObjectURL blob)
        a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (js/document.body.appendChild a)
    (.click a)
    (js/document.body.removeChild a)
    (js/URL.revokeObjectURL url)))

(defn- download-usda! []
  (download-blob! (js/Blob. #js [(usd-export/scene->usda (current-scene))] #js {:type "text/plain"})
                   "scene.usda"))

;; ── glTF export (M7: wires kotoba.amenominaka.gltf-export to a real
;; browser download, using the CURRENTLY selected presets — same pattern
;; as USD export above, just a binary Blob instead of a text one). ──

(defn- download-glb! []
  (let [bytes (gltf-export/scene->glb (current-scene))
        blob (js/Blob. #js [bytes] #js {:type "model/gltf-binary"})]
    (download-blob! blob "scene.glb")))

;; ── mount / view ──

(defn- on-canvas-ref [node]
  (when node
    (-> (webgpu/init! node)
        (.then (fn [ctx]
                 (swap! state assoc :webgpu-ctx ctx)
                 (recompute-scene!)))
        (.catch (fn [err] (js/console.error "webgpu/init! failed:" err))))))

(defn- viewport []
  [:canvas {:id "viewport" :width 1024 :height 768
            :ref on-canvas-ref
            :on-mouse-down on-mouse-down
            :on-mouse-move on-mouse-move
            :on-mouse-up on-mouse-up
            :on-mouse-leave on-mouse-up
            :on-wheel on-wheel}])

(defn- preset-field [label-text options value-key]
  (let [id (str "field-" (name value-key))]
    [:<>
     [:label {:html-for id} label-text]
     [preset-select options
      {:id id
       :value (if (= value-key :vegetation) (first (:vegetation @state)) (get @state value-key))
       :on-change (fn [e]
                    (let [v (.. e -target -value)]
                      (if (= value-key :vegetation)
                        (swap! state assoc :vegetation [v])
                        (swap! state assoc value-key v)))
                    (recompute-scene!))}]]))

(defn- camera-mode-button []
  (let [mode (:camera-mode @state)]
    [btn (str "Camera: " (if (= mode :fly) "Fly (WASD)" "Orbit"))
     (fn [_e] (toggle-camera-mode!))
     "toggle-camera-mode"]))

(defn- scene-editor-panel []
  (let [selected (selected-element)
        length (get-in selected [:quantities :length-m] 0)
        height (get-in selected [:geometry :profile :height] 0)
        thickness (get-in selected [:geometry :profile :thickness] 0)]
    [shape/panel
     [:div {:class "am-scene-editor"}
      [:h3 "Scene Hierarchy"]
      [:div {:id "scene-tree"}
       (for [e (scene-elements)]
         ^{:key (:id e)}
         [:button {:type "button" :class (str (ui/class-name :button)
                                               (when (= (:id e) (:selected-element @state)) " selected"))
                   :on-click #(swap! state assoc :selected-element (:id e))}
          (str (name (:kind e)) " · " (:name e))])]
      [:div {:class "am-scene-actions"}
       [btn "Add Wall" (fn [_] (add-wall!)) "add-scene-wall"]
       [btn "Delete" (fn [_] (delete-selected!)) "delete-scene-element"]]
      (when selected
        [:div {:class "am-property-editor"}
         [:label "Name"]
         [:input {:id "element-name" :default-value (:name selected)
                  :key (str "name-" (:id selected) "-" (:name selected))
                  :on-blur #(update-selected! :name (.. % -target -value))}]
         [:label "Length (m)"]
         [:input {:id "element-length" :type "number" :step 0.1 :default-value length
                  :key (str "length-" (:id selected) "-" length)
                  :on-blur #(update-selected! :length (js/parseFloat (.. % -target -value)))}]
         [:label "Height (m)"]
         [:input {:id "element-height" :type "number" :step 0.1 :default-value height
                  :key (str "height-" (:id selected) "-" height)
                  :on-blur #(update-selected! :height (js/parseFloat (.. % -target -value)))}]
         [:label "Thickness (m)"]
         [:input {:id "element-thickness" :type "number" :step 0.05 :default-value thickness
                  :key (str "thickness-" (:id selected) "-" thickness)
                  :on-blur #(update-selected! :thickness (js/parseFloat (.. % -target -value)))}]])
      [:div {:class "am-scene-actions"}
       [btn "Undo" (fn [_] (undo-building!)) "undo-scene"]
       [btn "Redo" (fn [_] (redo-building!)) "redo-scene"]
       [btn "Save" (fn [_] (save-project!)) "save-project"]
       [btn "Load" (fn [_] (load-project!)) "load-project"]]]
     {:surface :thick :elevation :flat}]))

(defn- env-panel []
  [shape/panel
   [:div {:class "am-env-panel"}
    [:h3 "Environment"]
    [preset-field "Weather" weather-options :weather]
    [preset-field "Terrain" terrain-options :terrain]
    [preset-field "Post FX" postfx-options :postfx]
    [preset-field "Vegetation" vegetation-options :vegetation]
    [:div {:class "am-export-row"}
     [camera-mode-button]
     [btn "Export USD" (fn [_e] (download-usda!)) "export-usd"]
     [btn "Export glTF" (fn [_e] (download-glb!)) "export-gltf"]]]
   {:surface :thick :elevation :flat}])

;; ── omni.timeline UI (M9) — record the current camera view as a
;; keyframe, scrub/play back the resulting path. ──

(defn- timeline-panel []
  (let [{:keys [timeline playhead playing?]} @state
        dur (tl/duration timeline)]
    [shape/panel
     [:div {:class "am-timeline-panel"}
      [:h3 "Camera Path"]
      [:div {:class "am-timeline-info"}
       (str (count timeline) " keyframe" (when (not= 1 (count timeline)) "s")
            ", " (.toFixed dur 1) "s")]
      [:input {:id "timeline-scrub" :type "range" :min 0 :max (max dur 0.001) :step 0.01
               :value playhead :disabled (zero? dur)
               :on-change (fn [e] (scrub-timeline! (js/parseFloat (.. e -target -value))))}]
      [:div {:class "am-timeline-row"}
       [btn "Record Keyframe" (fn [_e] (record-keyframe!)) "record-keyframe"]
       [btn (if playing? "Pause" "Play")
        (fn [_e] (if playing? (pause-timeline!) (play-timeline!)))
        "toggle-play"]
       [btn "Clear" (fn [_e] (clear-timeline!)) "clear-timeline"]]]
     {:surface :thick :elevation :flat}]))

(defn- debug-state []
  ;; Same idiom as M2/M4's `#out` div: a plain DOM text node a real-browser
  ;; nbb/Playwright script can poll, since WebGPU canvas pixel readback was
  ;; found unreliable in this Chromium build (see test/render/verify_m2_render
  ;; docstring) and there is no other externally-observable signal that a
  ;; preset change actually reached (state) and re-rendered.
  (let [{:keys [weather terrain postfx vegetation camera-mode fly timeline playhead playing? render-ir selected-element building]} @state]
    [:span {:id "debug-state" :style {:display "none"}}
     (js/JSON.stringify (clj->js {:weather weather :terrain terrain :postfx postfx :vegetation vegetation
                                   :cameraMode (name camera-mode) :flyPos (:pos fly)
                                   :elementCount (count (:elements (bim/find-storey building 3)))
                                   :selectedElement selected-element
                                   :keyframeCount (count timeline) :playhead playhead :playing playing?
                                   :renderEye (get-in render-ir [:globals :eye])}))]))

(defn- root []
  [:div {:class "am-shell"}
   [ui/nav-bar "kami-app-amenominaka" {:trailing nil}]
   [:div {:class "am-body"}
    [:div {:class "am-sidebar"}
     [scene-editor-panel]
     [env-panel]
     [timeline-panel]]
    [viewport]]
   [debug-state]])

(defonce root-node (atom nil))
(defonce keyboard-listeners-installed? (atom false))

(defn init! []
  (let [el (js/document.getElementById "app")]
    (when-not @root-node (reset! root-node (rdomc/create-root el)))
    (rdomc/render @root-node [root])
    (when-not @keyboard-listeners-installed?
      (reset! keyboard-listeners-installed? true)
      (js/window.addEventListener "keydown" on-keydown)
      (js/window.addEventListener "keyup" on-keyup))))
