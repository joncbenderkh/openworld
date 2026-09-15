# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working in this repository.

## Project

**openworld** — a procedurally generated Android game world rendered as an
actual 3D globe: a geodesic sphere (dual of a subdivided icosahedron —
hexagonal tiles plus exactly 12 pentagons, since a sphere can't be tiled by
hexagons alone) that the player spins with drag/twist gestures. Multiple
biomes: ocean, desert, river, lake, forest, mountain, swamp, plains,
savannah, each with its own procedurally generated tile texture (no external
art assets or image-gen tool available, so patterns are hand-coded pixel art
generated at startup — see `BiomeTextures.kt`).

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
  assets/   shared assets (currently none — tile art is generated at runtime, see BiomeTextures.kt)
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
  textured via a small procedural atlas from `BiomeTextures.kt` — each face's
  corners get UV coords arranged evenly around its biome's atlas cell, no real
  3D UV unwrapping), a minimal custom shader (texture × vertex color, so
  non-terrain geometry like the graticule can share it by sampling a reserved
  white texel and relying on its own vertex color), and a fixed `PerspectiveCamera`:
  drags accumulate a rotation quaternion applied to the globe's model matrix
  instead of orbiting the camera (arcball/trackball), which avoids the pole
  singularities a camera-orbit model runs into.
- No lighting or biome-color blending at tile boundaries yet.
