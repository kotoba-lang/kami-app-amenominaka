# kami-app-amenominaka (天之御中)

[![CI](https://github.com/kotoba-lang/kami-app-amenominaka/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/kami-app-amenominaka/actions/workflows/ci.yml)

Omniverse Kit-equivalent app shell + extension loader — kotoba `.cljc` port
of the retiring Rust `kami-app-amenominaka` crate (ADR-2607010000).

**Status**: R1.0 path reservation (ADR-2605261800) for the extension-loader
identity itself — the original 5 `pub const` port. **`kotoba.amenominaka.scene`
(M0 of ADR-2607100100, `com-junkawasaki/root`) is implemented**: a first
real capability, not just a name reservation — see below.

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

This is two slices of ADR-2607100100's Twinmotion-equivalent design (M0
scene composition + M1 USD export, of M0–M4); M2 (3D scene render-IR on
`kami-webgpu`) is not implemented yet.

## Scope (R1.4 deliverable — mostly NOT implemented)

The upstream README describes the following as the R1.4 deliverable.
Scene composition (above) is now real; everything else below is still
**not implemented**, here or upstream:

- `extension.toml` (Omniverse Kit format) loader
- Extension lifecycle (startup / shutdown / `depends_on` resolution)
- Internal mapping: extension → magatama Pregel cell
- 5 reference extension parity: `omni.usd` / `omni.kit.app` /
  `omni.replicator.core` / `omni.kit.viewport` / `omni.timeline`

Do not treat the presence of this README section as evidence that the
extension-loader shell itself is wired up — only the scene-composition
piece above is real.

## Maturity

| | |
|---|---|
| Role | extension-loader identity: path reservation. Scene composition + USD export: implemented (M0/M1, ADR-2607100100) |
| Tests | green (13 tests / 66 assertions: identity constants + scene composition + USD export) |
| R1.4 extension loader | not implemented (upstream nor here) |

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
