# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working in this repository.

## Project

**openworld** — a 2D Android game with a procedurally generated, top-down open
world on a hexagonal grid that wraps around like a globe (east-west wrap at
minimum; pole handling TBD once the projection is chosen). Multiple biomes:
ocean, desert, river, lake, forest, mountain, swamp, plains, savannah. For now
biomes are rendered as flat colors — no tile art yet.

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
  core/     platform-independent game logic (world gen, hex grid, rendering, biomes)
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

## World generation notes (for future work)

- Hex grid: axial or cube coordinates; a wrapping world needs the grid's
  horizontal extent to tile seamlessly (column `x` wraps mod width) — this
  affects noise sampling (must be seamless/periodic in that direction) and
  neighbor lookups at the seam.
- Biome assignment: derive from procedural noise fields, most likely
  elevation + moisture (+ temperature/latitude for savannah vs. forest vs.
  desert splits), thresholded into the nine biomes. Rivers/lakes likely need
  a separate hydrology pass (flow accumulation from elevation) rather than
  pure noise thresholding.
- Biomes are placeholder solid colors for now — no tile art or blending yet.
