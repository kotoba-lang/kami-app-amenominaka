# kami-app-amenominaka (天之御中)

[![CI](https://github.com/kotoba-lang/kami-app-amenominaka/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/kami-app-amenominaka/actions/workflows/ci.yml)

Omniverse Kit-equivalent app shell + extension loader — kotoba `.cljc` port
of the retiring Rust `kami-app-amenominaka` crate (ADR-2607010000).

**Status**: the original 5 `pub const` port ("R1.0 path reservation" per
ADR-2605261800) still stands as the upstream-identity contract below —
but the extension loader itself is now real. **`kotoba.amenominaka.scene`
(M0), `kotoba.amenominaka.usd-export` (M1), `kotoba.amenominaka.render-ir`
(M2), the extension loader (`kotoba.amenominaka.application` +
`kotoba.amenominaka.extension` + `kotoba.amenominaka.extensions`, M3), and
the interactive UI shell (`kotoba.amenominaka.ui`, M5) — all of
ADR-2607100100 (`com-junkawasaki/root`) — are implemented.**

## Scene composition (`kotoba.amenominaka.scene`, M0 of ADR-2607100100)

Composes a [`bim`](https://github.com/kotoba-lang/bim) building model with
named [`kami-atmosphere-scene`](https://github.com/kotoba-lang/kami-atmosphere-scene) /
[`kami-vegetation-scene`](https://github.com/kotoba-lang/kami-vegetation-scene) /
[`kami-terrain-scene`](https://github.com/kotoba-lang/kami-terrain-scene) /
[`kami-postfx-scene`](https://github.com/kotoba-lang/kami-postfx-scene) presets
into one walkthrough-ready scene EDN. Pure CLJC, no rendering.

```clojure
(require '[bim] '[kotoba.amenominaka.scene :as amenominaka-scene])

(def building
  (-> (bim/project "Quarry Walk Lodge")
      (update :sites conj (bim/site {:id 1 :name "Site 1" :geo nil
                                      :placement :identity :buildings [...]}))))

(def scene
  (amenominaka-scene/compose {:building   building
                               :weather    "overcast"
                               :vegetation ["grass"]
                               :terrain    "plains"
                               :postfx     "nintendo"}))
;; => {:scene/building ... :scene/atmosphere ... :scene/vegetation {"grass" ...}
;;     :scene/terrain ... :scene/postfx ...}

(amenominaka-scene/valid-scene? scene) ;; => true
```

`kami-cad-import` is deliberately **not** used for the building side: its
`VehicleAssembly` model is closed over vehicle part kinds/materials and its
geometry payload is AABB-only (confirmed by reading its source) — it cannot
stand in for architectural CAD/BIM import. See ADR-2607100100 Addendum
(2026-07-09) for this correction against the original design.

## USD export (`kotoba.amenominaka.usd-export`, M1 of ADR-2607100100)

Exports a `compose` result to a viewable `.usda` (USD ASCII) text document,
via [`kotoba-lang/usd`](https://github.com/kotoba-lang/usd)'s generic
USDA-from-EDN emitter. No new rendering engine — open the output in
Blender or any USD viewer.

```clojure
(require '[kotoba.amenominaka.usd-export :as usd-export])

(spit "lodge.usda" (usd-export/scene->usda scene))
(spit "default.mtlx" (usd-export/default-material-mtlx))
```

`bim`'s `ElementGeometry` (brep/axis-sweep/mesh-ref/none, carried on every
`bim/element`) has no bridge in `bim` itself to real triangle data
(confirmed by reading `bim.cljc` in full). This namespace computes a real
box mesh only for the one case simple enough to do honestly without
building a BREP tessellator — an axis-sweep element with a rectangle
profile (the common wall/beam case) — and exports every other geometry
kind as a plain `Xform` placeholder, not a faked mesh. Environment presets
(atmosphere/vegetation/terrain/postfx) carry no placement/heightfield/
instance data in M0's scene EDN, so they're recorded as `pr-str`'d custom
attrs on an `Environment` scope rather than fabricated geometry.
`materialx.core` produces one companion neutral-gray `.mtlx` document —
not yet bound into the `.usda` stage (a documented gap, not a guessed
UsdMtlx binding schema). glTF export is not implemented — M1 is USD-only.

## Real-time render-IR (`kotoba.amenominaka.render-ir`, M2 of ADR-2607100100)

Bridges a `compose` result into [`kotoba-lang/webgpu`](https://github.com/kotoba-lang/webgpu)'s
`kami.webgpu.ir` render-IR — the EDN a REAL, already-working browser
WebGPU executor (camera + shadow-mapped directional light + PBR
instancing) consumes. **Correction to ADR-2607100100 D5's original
premise**: `kotoba-lang/webgpu`'s renderer was NOT 2D/UI-only (that's
`dom-gpu`, a different repo) — it's a real 3D renderer already; M2's
actual new work was this EDN bridge, not the renderer.

```clojure
(require '[kotoba.amenominaka.render-ir :as render-ir])

(def ir (render-ir/scene->render-ir scene))
;; => {:globals {:sky {...} :eye [...] :target [...]} :instances [{:pos :color :size :yaw} ...]}
```

`ir/instance`'s box is **square-footprint only** (`:size [w h]` =
footprint-width × height — confirmed from `kami.webgpu.ir`'s own source).
Each `bim` axis-sweep element renders as a coarse massing block
(footprint = the sweep's run length, height = the profile's height) —
the same idiom `kami.webgpu.ir`'s own docstring uses for
`;; a building`, a legitimate early-stage massing representation, not a
mesh-accurate wall. brep/mesh-ref/no-geometry elements carry no
positional data and are skipped, not faked. A terrain preset (if
requested) adds one flat ground-plane instance tinted by the biome's
palette. Vegetation/postfx are not bridged — no placement data exists for
either in M0's scene EDN.

**Verified in a real browser**, not just structurally: `public/m2-demo.html`
(compiled via `shadow-cljs compile m2-demo`) composes the M0 sample
scene, draws one real frame via `kami.webgpu/init!`+`draw!`, and
`nbb -cp test/render test/render/verify_m2_render.cljs` drives a full
(non-headless-shell) Chromium via Playwright — reusing the technique
`kotoba-lang/wasm-webcomponent` proved in ADR-2607078000 Addendum 8,
ported to nbb rather than copied as `.mjs` — to confirm `navigator.gpu`
actually works and capture a screenshot. The captured screenshot shows a
real shaded sky/ground/massing-block render, not a blank canvas.

M2's interactive orbit/fly camera controls are not implemented (the
render-IR carries a static framed camera only).

## Perf investigation (`kotoba.amenominaka.render-stress-demo`, M4 of ADR-2607100100)

Before speculatively building `wgsl` `@compute` GPU-side instancing (the
ADR's original M4 direction), `public/m4-stress-demo.html` +
`nbb -cp test/render test/render/verify_m4_stress.cljs` measure whether
M2's CPU-authored instancing actually hits a wall — a grid of N walls at
several scales, drawn 60 real frames in a real browser, reporting
avg/p95/max frame time. It found one: **the real wall was in
`kotoba-lang/webgpu`'s `draw!`**, not a GPU capacity limit — it
unconditionally re-sorted/re-marshaled/re-uploaded the entire instance
buffer every frame even for a completely static scene. Fixed upstream
(`kotoba-lang/webgpu`, instance-buffer caching keyed on `(:instances ir)`
by reference) rather than pursuing `wgsl @compute`, which wouldn't have
addressed this specific bottleneck. Measured improvement: 15,000
instances 34.0ms→0.85ms/frame (~40×). This demo/harness is kept as a
reusable perf-regression check, not a one-time throwaway.

## Extension loader (`kotoba.amenominaka.application`+`.extension`+`.extensions`, M3 of ADR-2607100100)

The `application`/`extension` protocols are **ported forward from
[`kotoba-lang/kami-nv-compat`](https://github.com/kotoba-lang/kami-nv-compat)'s**
`kotoba.lang.kami-nv-compat.amenominaka.{application,extension}` (a
clean-room `omni.kit.app.IApp`/`omni.ext.IExt` mirror, ADR-2605261800
D6/D10.4) **into this repo as the canonical implementation** — per that
same ADR's own N10 principle that the nv-compat facade shouldn't hold
substantive logic. The loader logic itself is unchanged: `register-
extension!`/`unregister-extension!`/`startup-all`/`update!`/`shutdown-
all` on an `IApplication`, Kahn topological sort over each extension's
`:dependencies` (parents before children on startup, reverse on
shutdown, a cycle throws), plus `parse-extension-toml` — a real
hand-rolled parser for the literal-`.toml`-compat case (`kotoba-lang/toml`
was considered but is EDN→TOML *only*, confirmed by reading its source —
it can't read a literal `extension.toml`).

`kotoba.amenominaka.extensions` registers M0 (`scene`) and M2
(`render-ir`, `:dependencies {"scene" {}}`) as `extension.edn`-manifested
extensions and starts them in dependency order:

```clojure
(require '[kotoba.amenominaka.extensions :as extensions])

(extensions/load!)
;; => {:app #object[...] :order ["scene" "render-ir"] :log [[:startup "scene"] [:startup "render-ir"]]}
```

`extension.edn` manifests are plain EDN data (no custom reader needed —
same shape `parse-extension-toml` produces), authored in-code rather
than as separate files: this repo is a single app, not a multi-package
plugin directory a loader scans at runtime, so there's no real
filesystem-discovery use case yet to build for.

**On "extension → magatama Pregel cell"** (the upstream README's R1.4
scope item this section otherwise closes): re-reading ADR-2605261800 in
full finds **zero occurrences of either word** — the attribution doesn't
survive the source. "magatama" independently names three unrelated
systems elsewhere in this monorepo (a gftdcojp Cloudflare-Worker app
convention, an etzhayyim capital-flow actor, gftdcojp's pymagatama/keiei
daemon) with no Pregel-flavoured technical definition anywhere; the
org's real Pregel-cell system is `kotodama`'s persistent-daemon catalog
(ADR-2605192415), unrelated to extension loading. **This mapping is
retired as a concept here, not deferred as a gap** — extensions have a
real dependency-ordered lifecycle without it.

## Interactive UI shell (`kotoba.amenominaka.ui`, M5 of ADR-2607100100)

`public/shell.html` (compiled by shadow-cljs's `:shell` build,
`kotoba.amenominaka.ui/init!`) is the first real interactive surface over
M0–M2's pipeline — before M5 the only way to see a scene was a static
single-frame demo page. It's built on the org's **default UI/UX design
system** (ADR-2607022800): `shitsuke` (dual-render structure) +
`liquid-glass-ui` (material skin) + `kotoba-ui` (single require point) +
`appkit` (desktop binding — panels/toolbar/dropdowns, not touch/card-first,
so `appkit` over `uikit`). This is DOM chrome (env panel, nav bar) around a
`<canvas>` leaf that M2's `kami.webgpu` draws into directly — the same
"DOM siblings around a canvas" shape as the pre-existing `kami-engine-hud`,
since `liquid-glass-ui` is DOM-only by design (true in-canvas glass
rendering, `liquid-glass.gpu`, is explicit future work upstream, confirmed
absent by reading its own ADR/design docs before starting).

**Environment controls**: four dropdowns (Weather/Terrain/Post FX/
Vegetation) drive `kotoba.amenominaka.scene/compose` on every change,
re-bridge through `kotoba.amenominaka.render-ir/scene->render-ir`, and
redraw. Preset ids are the real shipped ones, read directly from each
`kami-*-scene` repo's own EDN rather than guessed (weather: overcast/
clear; terrain: plains/quarry/desert/tundra; vegetation: grass/fern/palm/
conifer/bush/cactus/moss; postfx: nintendo/retro/final-fantasy/
baminiku-character).

**Orbit/zoom camera**: mouse-drag orbits, wheel zooms/dollies. Camera
moves only `assoc-in` `:eye`/`:target` into the *existing* render-IR —
`:instances` is never touched on a camera-only frame, so M4's
instance-buffer cache (~40× at scale, `identical?`-keyed) stays hot during
every drag/zoom frame; only an actual preset change rebuilds `:instances`
and pays a fresh upload.

**USD export**: an "Export USD" button wires M1's `usd-export/scene->usda`
(using the *currently selected* presets) to a real browser download via
`Blob`+`URL.createObjectURL`.

**Two confirmed real `kotoba-ui` bugs, routed around, not patched
upstream**: `menu-select` renders an uncontrolled `<select>`/`<option>`
(no `:value`/`:key` React props — React would warn and the displayed value
wouldn't track external state changes), and `button` has no `:on-click`
support at all (only shitsuke's SSR `:act` contract, meaningless in a live
CLJS app). `preset-select`/`btn` in `ui.cljs` are small hand-rolled
controlled replacements with the *same DOM shape* (so the generated CSS
still targets them) — the exact workaround, DOM-shape-preservation
reasoning included, ported from `murakumo-studio/src/murakumo_studio/
ui.cljs`'s real, independently-reproduced fix for the identical bugs.

**Static CSS**: `scripts/gen_kotoba_ui_css.clj` (`bb ui-css`) calls
`liquid-glass.tokens/css-variables`+`resolve-dark-tokens` and
`liquid-glass.style/component-css` — pure functions over EDN rule data,
no build step — and writes `public/vendor/kotoba-ui.css` (referenced via
a plain `<link>`, not runtime-injected), same shape as `murakumo-studio`'s
own CSS-gen script. This app is always-dark (a real-time 3D viewport has
no light-mode use case), so Tier A tokens are emitted directly rather than
gated on `prefers-color-scheme`.

**Real-browser verification** (`nbb -cp test/render
test/render/verify_m5_ui.cljs`) drives the actual compiled `shell.html` in
a full (non-headless-shell) Chromium and asserts, against the live app —
no mocks: the env panel's four `<select>`s + `#viewport` canvas exist
after mount; a `#debug-state` DOM node (same idiom as M2/M4's `#out`,
since WebGPU canvas pixel readback was found unreliable in this Chromium
build) reflects the default preset state on load; driving a real
`page.selectOption` on the weather dropdown actually flows through
React's `onChange` → `recompute-scene!` and is reflected in
`#debug-state` — concrete behavioral proof the controlled-select
workaround works, not just that it compiles; dragging `#viewport` changes
the rendered frame, compared via two real Chromium screenshots (not JS
canvas readback); and no console errors were logged. One real,
independently-confirmed bug was found and fixed by this verification
itself: the harness's static test server (`test/render/lib/
webgpu_harness.cljs`) had no `.css` MIME-type entry, so Chromium served
`vendor/kotoba-ui.css` as `application/octet-stream` and silently refused
to apply it as a stylesheet — fixed by adding `".css" "text/css;
charset=utf-8"` to its `mime-types` map.

## Scope (R1.4 deliverable)

The upstream README describes 5 reference-extension parities as the
R1.4 deliverable:

| Upstream item | Status here |
|---|---|
| `extension.toml` loader | done (`parse-extension-toml`, above) |
| Extension lifecycle (`depends_on`) | done (Kahn topo sort, above) |
| extension → magatama Pregel cell | **retired**, not implemented (see above) |
| `omni.usd` parity | done (M1 USD export) |
| `omni.kit.viewport` parity | done (M2 render-IR + real-browser walkthrough) |
| `omni.kit.app` parity | done (the loader itself, M3) |
| `omni.timeline` parity | **not implemented** (keyframe/camera-path — ADR-2607100100 D7 marks this M3-stretch, not required) |
| `omni.replicator.core` parity | **out of scope** (synthetic-data/domain-randomization — ADR-2607100100 D6 explicit non-goal, deferred to a future ADR) |

The R1.4 gate as upstream defined it (all 5 extensions working) does not
fully close — `omni.replicator.core` is a permanent non-goal here, so it
structurally can't. Four of five are real; `omni.timeline` is the one
genuine remaining gap.

## Maturity

| | |
|---|---|
| Role | Scene composition + USD export + render-IR bridge + extension loader + interactive UI shell: all implemented (M0–M3 + M5, ADR-2607100100); M4 investigated + real fix landed upstream |
| Tests | green (29 tests / 122 assertions), plus real-browser WebGPU smoke (screenshot-verified) + perf-regression checks + UI-shell interaction checks, all green on GitHub Actions macOS |
| R1.4 extension loader | implemented (this repo); `omni.timeline` and `omni.replicator.core` parity remain out of scope/not implemented |
| Perf | M2's CPU-authored instancing verified performant to 20k+ instances after the M4 fix landed in `kotoba-lang/webgpu` (was a real ~29fps wall at 15k instances before) |
| UI/UX | M5: environment-preset controls + orbit/zoom camera + USD export, on the org's default `shitsuke`+`liquid-glass-ui`+`kotoba-ui`+`appkit` stack (ADR-2607022800), real-browser interaction-verified |

## Contract

```clojure
(require '[kotoba.amenominaka :as amenominaka])

amenominaka/adr                       ;; => "ADR-2605261800"
amenominaka/phase                     ;; => "R1.0-path-reservation"
amenominaka/kami-name                 ;; => "amenominaka"
amenominaka/nv-compat-target          ;; => "Omniverse Kit (app shell + extension system)"
amenominaka/extension-manifest-format ;; => "extension.toml"

amenominaka/path-reservation
;; => {:adr "ADR-2605261800"
;;     :phase "R1.0-path-reservation"
;;     :kami-name "amenominaka"
;;     :nv-compat-target "Omniverse Kit (app shell + extension system)"
;;     :extension-manifest-format "extension.toml"}
```

No network, no I/O. Portable `.cljc` across JVM / ClojureScript / SCI /
GraalVM.

## License

Apache License 2.0.
