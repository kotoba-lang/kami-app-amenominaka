# kami-app-amenominaka (天之御中)

[![CI](https://github.com/kotoba-lang/kami-app-amenominaka/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/kami-app-amenominaka/actions/workflows/ci.yml)

Omniverse Kit-equivalent app shell + extension loader — kotoba `.cljc` port
of the retiring Rust `kami-app-amenominaka` crate (ADR-2607010000).

**Status**: R1.0 path reservation (ADR-2605261800). This is a faithful 1:1
port of the upstream crate: **the upstream Rust source is nothing but 5
`pub const` identity/scope strings — a name reservation, not an
implementation.** This port carries over exactly that: the same 5
constants as Clojure `def`s, plus a convenience map. Nothing else exists
to port.

## Scope (R1.4 deliverable — NOT implemented, here or upstream)

The upstream README describes the following as the R1.4 deliverable. None
of it is implemented in the upstream Rust source, and consequently **none
of it is implemented in this port either**:

- `extension.toml` (Omniverse Kit format) loader
- Extension lifecycle (startup / shutdown / `depends_on` resolution)
- Internal mapping: extension → magatama Pregel cell
- 5 reference extension parity: `omni.usd` / `omni.kit.app` /
  `omni.replicator.core` / `omni.kit.viewport` / `omni.timeline`

Do not treat the presence of this README section as evidence that any of
the above is wired up — it is an aspirational scope note carried over
verbatim from upstream, kept here so the gap is visible rather than
silently dropped.

## Maturity

| | |
|---|---|
| Role | path reservation (name + scope identity only) |
| Tests | green (constants only) |
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
