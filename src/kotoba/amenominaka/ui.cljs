(ns kotoba.amenominaka.ui
  "M5 (ADR-2607100100): the interactive UI shell — real environment-preset
  controls (weather/terrain/postfx/vegetation) and an orbit/zoom camera
  over the WebGPU viewport, wired to M0-M2's already-landed pipeline
  (previously only reachable via a static single-frame demo).

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
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdomc]
            [appkit.core :as shape]
            [kotoba-ui.core :as ui]
            [bim]
            [kotoba.amenominaka.scene :as amenominaka-scene]
            [kotoba.amenominaka.render-ir :as render-ir]
            [kotoba.amenominaka.usd-export :as usd-export]
            [kami.webgpu :as webgpu]
            [kami.webgpu.ir :as ir]))

;; ── controlled-component workarounds (see ns docstring) ──

(defn- btn [label on-click]
  [:button {:class (ui/class-name :button) :type "button" :on-click on-click} label])

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
           :camera {:azimuth 0.785 :distance 64.0 :height 55.0}
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

(defn- apply-camera! []
  (let [{:keys [render-ir camera pivot webgpu-ctx]} @state]
    (when (and render-ir pivot)
      (let [{:keys [eye target]} (ir/rig->camera camera pivot)
            ir' (-> render-ir (assoc-in [:globals :eye] eye) (assoc-in [:globals :target] target))]
        (swap! state assoc :render-ir ir')
        (when webgpu-ctx (webgpu/draw! webgpu-ctx ir'))))))

(defn- current-scene []
  (let [{:keys [weather terrain postfx vegetation]} @state]
    (amenominaka-scene/compose {:building (sample-building)
                                 :weather weather :terrain terrain :postfx postfx
                                 :vegetation vegetation})))

(defn- recompute-scene! []
  (let [base-ir (render-ir/scene->render-ir (current-scene))
        target (get-in base-ir [:globals :target])
        pivot [(nth target 0) (nth target 2)]]
    (swap! state assoc :render-ir base-ir :pivot pivot)
    (apply-camera!)))

;; ── mouse/wheel orbit camera (M2's own gap: "interactive orbit/fly
;; camera controls are not implemented" — closed here) ──

(defn- on-mouse-down [e]
  (swap! state assoc :dragging? true :last-x (.-clientX e) :last-y (.-clientY e)))

(defn- on-mouse-move [e]
  (when (:dragging? @state)
    (let [{:keys [last-x last-y]} @state
          dx (- (.-clientX e) last-x)
          dy (- (.-clientY e) last-y)]
      (swap! state assoc :last-x (.-clientX e) :last-y (.-clientY e))
      (swap! state update :camera
             (fn [c] (-> c
                         (update :azimuth + (* dx 0.01))
                         (update :height #(max 5.0 (- % (* dy 0.5)))))))
      (apply-camera!))))

(defn- on-mouse-up [_e] (swap! state assoc :dragging? false))

(defn- on-wheel [e]
  (.preventDefault e)
  (swap! state update :camera update :distance
         (fn [d] (max 10.0 (min 400.0 (+ d (* (.-deltaY e) 0.1))))))
  (apply-camera!))

;; ── USD export (wires M1's kotoba.amenominaka.usd-export to a real
;; browser download, using the CURRENTLY selected presets) ──

(defn- download-usda! []
  (let [text (usd-export/scene->usda (current-scene))
        blob (js/Blob. #js [text] #js {:type "text/plain"})
        url (js/URL.createObjectURL blob)
        a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) "scene.usda")
    (js/document.body.appendChild a)
    (.click a)
    (js/document.body.removeChild a)
    (js/URL.revokeObjectURL url)))

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

(defn- env-panel []
  [shape/panel
   [:div {:class "am-env-panel"}
    [:h3 "Environment"]
    [preset-field "Weather" weather-options :weather]
    [preset-field "Terrain" terrain-options :terrain]
    [preset-field "Post FX" postfx-options :postfx]
    [preset-field "Vegetation" vegetation-options :vegetation]
    [:div {:class "am-export-row"} [btn "Export USD" (fn [_e] (download-usda!))]]]
   {:surface :thick :elevation :flat}])

(defn- debug-state []
  ;; Same idiom as M2/M4's `#out` div: a plain DOM text node a real-browser
  ;; nbb/Playwright script can poll, since WebGPU canvas pixel readback was
  ;; found unreliable in this Chromium build (see test/render/verify_m2_render
  ;; docstring) and there is no other externally-observable signal that a
  ;; preset change actually reached (state) and re-rendered.
  (let [{:keys [weather terrain postfx vegetation]} @state]
    [:span {:id "debug-state" :style {:display "none"}}
     (js/JSON.stringify (clj->js {:weather weather :terrain terrain :postfx postfx :vegetation vegetation}))]))

(defn- root []
  [:div {:class "am-shell"}
   [ui/nav-bar "kami-app-amenominaka" {:trailing nil}]
   [:div {:class "am-body"}
    [env-panel]
    [viewport]]
   [debug-state]])

(defonce root-node (atom nil))

(defn init! []
  (let [el (js/document.getElementById "app")]
    (when-not @root-node (reset! root-node (rdomc/create-root el)))
    (rdomc/render @root-node [root])))
