# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working in this repository.

## Project

**openworld** — a procedurally generated Android game world rendered as an
actual 3D globe: a geodesic sphere (dual of a subdivided icosahedron —
hexagonal tiles plus exactly 12 pentagons, since a sphere can't be tiled by
hexagons alone) that the player orbits/rotates around. Multiple biomes:
ocean, desert, river, lake, forest, mountain, swamp, plains, savannah. For now
biomes are rendered as flat colors — no tile art yet.

A flat 2D hex map with edge-wrapping was tried first and rejected — no matter
how the wrap topology worked (cylinder wrap, then pole wrap-over), it read as
a "conveyor belt," not a globe, because a flat grid can't show curvature. The
3D sphere fixes that at the source.

## Decisions

- **License:** GPL-3.0 (see `LICENSE`).
- **Stack:** Kotlin + [libGDX](https://libgdx.com/), targeting Android.
  Chosen over Jetpack Compose Canvas (too little built-in game-loop/camera
  support), Godot, and Unity (heavier tooling than a code-first solo
  prototype needs).
- **Git identity:** repo-local `user.email` = `joncbender@gmail.com`,
  `user.name` = `Jon Bender` (set explicitly; do not rely on inherited global
  config).

## Layout (libGDX standard)

```
openworld/
  core/     platform-independent game logic
    geo/    geodesic sphere geometry (icosahedron subdivision + dual)
    ...     world generation (noise → biomes → rivers/lakes), 3D rendering, biomes
  android/  Android application module (libGDX Android backend)
  assets/   shared assets (currently none — biomes are solid colors)
```

## Build, test, lint

- Build/assemble: `./gradlew build`
- Run unit tests: `./gradlew test`
- Install debug APK to a connected device/emulator: `./gradlew android:installDebug`
- Lint: `./gradlew lint`

## Versioning

Starts at `0.1.0` (`versionName` in `android/build.gradle`, mirrored in
`core` if a version constant is added later). Releases are cut by tagging
`vX.Y.Z`; the manifest version is the source of truth, the tag mirrors it.

## CI / Release

- `.github/workflows/ci.yml` — build + test + lint on push to `main` and on PRs.
- `.github/workflows/release.yml` — on `v*` tags, builds the Android artifact
  and publishes it to a GitHub Release. **Unsigned** until a release keystore
  is provisioned as repository secrets (`ANDROID_KEYSTORE_BASE64`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) —
  ask the user before ever generating a signing key, since losing it is
  unrecoverable.

## World generation notes

- Sphere geometry (`geo/GeodesicSphere.kt`): subdivide a regular icosahedron
  (frequency `n` → `20n²` triangles), then take the dual — every subdivided
  vertex becomes a face (hexagon), except the original 12 icosahedron
  vertices (pentagons). Total tiles = `10n² + 2`. Sampling noise directly at
  each face's 3D center has no seams to stitch, unlike a flat wrapped map.
- Biome assignment: elevation + moisture 3D fBm noise (`Noise3D.kt`), plus
  latitude from the sphere's Y coordinate, thresholded into the nine biomes
  (`WorldGenerator.kt`). Rivers/lakes are a separate hydrology pass
  (steepest-descent walk / local-minima detection) over the sphere's
  face-adjacency graph — no map edges to special-case at all.
- Rendering (`GlobeScreen.kt`): one static `Mesh` (fan-triangulated per face,
  flat per-vertex color, no lighting/texturing), a minimal custom shader, and
  a `PerspectiveCamera` orbiting the sphere at fixed radius (spherical
  coordinates `theta`/`phi` driven by pan gestures, `distance` by pinch-zoom).
- Biomes are placeholder solid colors for now — no tile art or blending yet.
