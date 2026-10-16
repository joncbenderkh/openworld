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
  desktop/  Linux desktop launcher (libGDX LWJGL3 backend): keyboard/mouse input and a text overlay
  assets/   shared assets (currently none — tile art is generated at runtime, see BiomeTextures.kt)
```

## Build, test, lint

- Build/assemble: `./gradlew build`
- Run unit tests: `./gradlew test`
- Install debug APK to a connected device/emulator: `./gradlew android:installDebug`
- Lint: `./gradlew lint`
- Run on the desktop: `./gradlew :desktop:run` (extra settings via `--args`, e.g.
  `--args="--max-near-patches=1024"`, or `-Dopenworld.maxNearPatches=1024`; a desktop JVM
  has no 256 MB heap limit, so the heap-driven near-patch cap can be raised there; set heap with
  `-Popenworld.heap=4g`, or `JAVA_OPTS=-Xmx...` for the `installDist` start script). World caches live in `$XDG_DATA_HOME/openworld`, preferences in
  `$XDG_CONFIG_HOME/openworld`. Keys and mouse are listed in the on-screen help (H toggles it).

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
  latitude from the sphere's Y coordinate, thresholded into the biomes
  (`WorldGenerator.kt`). Mountains and foothills also need a ridged-noise
  field, so they form chains, not blobs; rivers rise only in mountains.
  Changing world generation means bumping the cache versions. Rivers/lakes are a separate hydrology pass
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
- Level of detail (`GlobeScreen.kt`, `TerrainLayer.kt`, `CoarseWorld.kt`, `NearPatchStreamer.kt`,
  `PatchLayout.kt`): the world is drawn at two levels. A coarse far layer (frequency 199, each tile a
  weighted vote of the fine tiles inside it, with rivers and lakes weighted up so they survive) is always
  drawn. The full-resolution near layer is built patch by patch on demand, only below camera distance ~2.2,
  within a per-frame time budget. Both layers are split into compact `PatchLayout` cells with bounding
  `SphereCap`s so culling against the horizon and view frustum is tight.
- The world itself is compact (`WorldCompactor.kt`, `CompactPlan.kt`, `CompactWorldCache.kt`,
  `TilePicker.kt`): it is generated at full resolution (frequency 540, ~2.9M tiles), then every tile far from
  land is replaced. Tile ids `0 until fineCount` are fine tiles (land, a 3-tile coast, and any frequency-66
  coarse cell that holds those); ids from `fineCount` on are one large ocean tile per all-ocean coarse cell
  (~26k of them), with their own corners and any number of neighbors (`Sphere.neighborStart`). That is
  ~41% of the tiles, ~74 MB of arrays instead of ~175 MB. The full world exists only inside
  `GlobeScreen.generateAndCacheWorld`, which streams the compact world to the cache and returns, so the
  full world is freed *before* the compact one is loaded (ART's interpreter keeps a dead reference in a
  live frame, so do not hold the full world in a caller's frame). Tap-picking resolves a point to its
  coarse cell first (`TilePicker`): an ocean tile owns its cell, otherwise the nearest fine tile wins.
- Cache files (`CacheFiles.kt`, `CompactWorldCache.kt`, `CoarseWorldCache.kt`) are written to a temporary
  sibling and renamed over the target, so a failed write never leaves a partial file; `save` returns
  whether it wrote. `CompactWorldCache.load` rejects a file whose indices do not point at real tiles. If
  the world cannot be stored (not enough free storage, or a write fails), `GlobeScreen` leaves the globe
  bare - underlay only, taps ignored - and tells the player through `onWorldProblem` (a typed `WorldProblem` and the seed: the Android error screen in `NewWorldFlow`, the
  overlay on the desktop) instead of crashing; `regenerateWorld` checks free storage first and keeps the
  current world if there is not enough.
- Open ocean (`DeepOcean.kt`, `GlobeScreen.kt`): ocean tiles with no land within 2 (far) / 3 (near) tiles are
  deliberately *omitted* from the layers - `PatchLayout`'s `keep` mask leaves them out of every patch (the
  big ocean tiles are always left out of the near layer), so missing meshes in deep ocean are intentional,
  not a bug. A coarse all-ocean sphere (`UNDERLAY_FREQUENCY` 40, one flat color via the shader's white texel
  times the vertex color) sits under everything and shows through. Draw order is underlay, far, near, each
  pushed behind the next with `glPolygonOffset` (underlay 4/8, far 2/4 while the near layer is up, near
  none), so a new draw call must respect it.
- The full-size world is generated without corners (`GeodesicSphere.generate(f, corners = false)`,
  `Sphere.hasCorners`): generation reads only centers and neighbors, and corners are 55% of the sphere's
  arrays. The neighbor lists are built by the same winding walk over a range of vertices at a time, so they
  are identical to the full sphere's. `CompactPlan` rebuilds a kept tile's corners from centers and neighbors:
  the corner between two consecutive neighbors is named by the neighbor-list slot of the lowest of the three
  tiles meeting there, and sits at the average of their centers. Measured on a desktop JVM (smallest `-Xmx`
  that cold-starts): frequency 445 needs ~150 MB, down from ~230; 540 needs ~180; 600 ~240.
- Camera (`CameraRig.kt`): the globe has radius 1 and the camera sits 1.04..6 from its center. Zoom scales the
  camera's *height above the surface* (`distance - 1`), not the distance, so the last steps do not leap. The near
  and far clip planes follow the height (near is half of it), which keeps depth precision where the surface is
  and the near plane out of the terrain; dragging slows with the height so the surface tracks the finger; the
  graticule is not drawn below distance 1.3 because its fixed-width lines would cover the tiles. At the closest
  zoom a full-resolution tile (frequency 540) is ~100 px wide.
- Selection (`TileHighlight.kt`): tapping a tile outlines it with a ring between its polygon shrunk and grown about
  its center (own small mesh). It lies on the tile itself and is drawn last over a cleared depth buffer (a ring lifted
  off the surface slides off the tile at a close zoom: parallax); a tile past the horizon is skipped. The ring is at
  least a few pixels thick at any zoom (`CameraRig.pixelSize`, more on a dense screen), so a tile too small to see is
  still marked. It works for open-ocean tiles too, which have their own corners. `GlobeScreen.clearSelection()`
  removes it (the Android panel's X, the desktop's X key); a new or reloaded world clears it.
- Memory is the binding constraint at this size (a 256 MB Java heap, no `largeHeap`): generation builds
  a full sphere without corners (~120 MB at 540: centers, starts and neighbors), so anything that holds two worlds at once (see `regenerateWorld`) or boxes
  per-tile values will OOM. Keep per-tile data in primitive arrays.
- Frequency 540 on the phone (2,916,002 source tiles, 1,171,533 after compaction): cache file 116 MB, warm world
  load 0.73 s, cold start (generate + save) ~13 s on 2026-10-14 (about 26 s before the compact cache and corner-free generation work), drag fps unchanged (~60 vsync-capped, ~120 idle).
  Headroom with a ballast array: warm 130 OK / 160 fail (~145 MB), cold 62 OK / 70 fail (~65 MB, was 30-40 at
  445). The numbers below are from frequency 445, before this.
- Measured on the phone (Android, seed 123456789; 1,980,252 source tiles, 809,555 after compaction). Warm
  start: Java heap 196 -> 94 MB once loaded, PSS 403 -> 311 MB (zoomed out), cache file 180 -> 80 MB, world
  load 0.78 -> 0.33 s, drag fps unchanged (~120 out, ~105 in). Headroom (heap held back by a ballast array
  before a start still succeeds): warm 60-80 -> ~160 MB. Cold start (generating) measured before the corner-free
  generation (#64): ~250 MB peak, ~30-40 MB headroom; after it the desktop floor fell from ~230 to ~150 MB (not yet
  re-measured on the phone). The 192 MB-heap
  emulator can now generate this world.

- Tile art (`BiomeArt.kt`, `BiomeTextures.kt`): one 512x512 atlas of 64 px cells, three pixel-art variants per
  biome (drafted by Codex from a written spec, then integrated and tuned). A tile picks its variant and, unless its
  biome is a relief biome (mountain, foothills, volcano: painted with a fixed upper-left light, so never rotated),
  one of six rotations from a hash of its id (`BiomeTextures.look`). Every cell averages to `BiomeArt.meanColor` (the
  biome's color; ocean averages to the flat underlay's blue so the two blend). The atlas has mipmaps built by
  halving, which never mixes two cells down to level 6 (a cell is 64 px); min filter `MipMapNearestLinear` (nearest
  texel within a level, linear between levels), mag `Nearest`. A faraway tile therefore shows its biome's mean color
  instead of grain. `core/build/atlas-preview.png` (written by `BiomeArtTest`) shows every cell at 3x.
- Resource icons (`ResourceIconArt.kt`, `ResourceIcons.kt`, `ResourceIconLayer.kt`): a 28-glyph atlas of its own (the 18 resources, then the 10 crafted products, numbered by `Items.slot`; 8 x 4 cells of 32 px, linear
  filter, drawn blended with the terrain shader). Below camera distance 1.25 (off above 1.3) each visible near patch gets one small icon mesh
  (a north-facing quad per resource, laid out by count inside the hexagon); built 2 per frame, released with the patch, ~1.5 s after it leaves the view,
  or when the camera pulls back. `core/build/icon-preview.png` (written by `ResourceIconArtTest`) shows every glyph at 4x.
- Lighting (`Lighting.kt`, `GlobeScreen` shaders): one light fixed in view space (upper left), applied per vertex as ambient 0.62 + diffuse 0.38 * max(dot(position, light), 0) - a unit sphere's position is its normal. `u_lit` is 1 for terrain and 0 for the graticule and selection ring. No biome-color blending at tile boundaries yet.
- Gathering (`Gathering.kt`, `Deposits.kt`, `Inventory.kt`): each resource slot on a claimed tile is a deposit, keyed `tile * 4 + slot`. Renewable
  ones yield forever; finite ones draw down a 300-unit store, and what is taken is saved per world (the `deposits` preference, cleared with a new
  seed). That key depends on the resource order per tile, so a change to resource generation must clear it too. A settlement works only deposits
  its tier allows, its people split over the resources by the player's weights (`Settlement.allocation`, saved with the settlement; none means even over the deposits), and the yield goes to one shared stockpile capped by tier storage. Mining runs in the
  2 s settlement tick (`Gatherer.tick`); the panel's income reads the same allocation (`Gatherer.economy`).
- Happiness (`Happiness.kt`): derived, never stored - 0..1 from the worst-met need, variety of need-meeting resources on the land, luxuries
  (`Tuning.LUXURIES`) in the stockpile and crowding near the tier's cap. Output (gathering, and growth when positive) falls linearly from full at
  `Tuning.CONTENT` to none at `Tuning.UNREST`; below it people leave. Every number is in `HappinessModel.Tuning`. The panel lists the grievances.
- Crafting (`Item.kt`, `Recipes.kt`, `Crafting.kt`): the stockpile holds `Item`s - raw `Resource`s and crafted `Product`s (`Items.slot` numbers
  them densely; the icon atlas uses it). Products are never on a tile, so world generation and its caches only know `Resource`.
  Recipes are a data table (`Recipes.ALL`: inputs, amount, ticks, minimum tier; a test checks none makes units from nothing or loops). A settlement
  crafts every recipe its tier allows and the player has not switched off (`Settlement.recipesOff`, saved under the `recipes_off` key with the
  world seed), at a rate set by its people and mood (`Crafting.batches`), from the shared stockpile, in the 2 s tick after gathering (`Crafter`).
  A recipe rests once `Crafting.Tuning.TARGET` of its product is held, so inputs do not drain into goods nothing uses. Effects (a good counts as stocked from `HappinessModel.Tuning.STOCKED` = 10 units, the same rule as luxuries): tools (`Gathering.CRAFTED_TOOLS`) raise every yield, clothing meets WARMTH, jewelry is a luxury. Product counts are saved with the world seed (`inventory_products_seed`). Settlements craft in list order from the shared stock, so with scarce inputs the first one wins (as gathering shares room). The panel opens one of its lists (assignments, crafting) at a time.
- Buildings (`Buildings.kt`): a settlement holds one of each of 12 `Building`s, in slots by tier (2/4/7/12). `Buildings.spec` is the data: a one-time
  cost from the stockpile, an upkeep paid every `UPKEEP_EVERY` ticks (`Upkeeper`: all or nothing per building, settlements in order; an unpaid one is idle,
  `Settlement.idle`, saved under `buildings_idle`, until the next payment; the cycle's place is kept under `upkeep_ticks`, so a restart neither skips nor repeats a payment nor revives an unpaid building) and the effect: the five gatherers raise their resources' yield (`GATHER_BONUS`) and need one on a
  claimed tile; a recipe needs its building (`Recipe.requires`); a warehouse adds storage, a working granary food units, a shrine and a market mood.
  Effects read `Settlement.working()` (built minus idle). Built sets are saved like `recipes_off`, under `buildings` with the world seed. The Android panel opens
  one list at a time (assign, crafting, buildings); the desktop builds with V and shows what is built.
- Inked-map tileset (`InkArt.kt`, `BiomeTextures.INK`): parchment tinted per biome with pen glyphs (peaks, trees, reeds,
  waves). Glyphs have an up, so with `INK` tiles are not rotated at random: `BiomeTextures.uprightUV` gives each corner
  the UV of its true bearing on the sphere, so glyphs stand north-up. Set `INK = false` for the pixel-art set
  (`BiomeArt.kt`). `BiomeTextures.meanColor` is measured from the painted cells, so the flat ocean underlay matches.
- Android chrome theme (`InkPalette.kt` in core, `InkTheme.kt` in android): the controls are printed onto the map - ivory
  surfaces, an ink edge, serif type. The two inks are `InkArt`'s pen colors (a test keeps them equal), so chrome and tiles read
  as one drawing; `InkPalette` is pure Kotlin so contrast (text >= 4.5:1, ink on ivory >= 7:1) and the 48 dp touch rule are unit
  tested. `InkTheme` builds the views (medallion, primary/secondary/text buttons, segmented control, a canvas-drawn `InkSwitch`,
  bottom sheet) from shapes, not from the app's old non-Material base theme, which renders stock Switch widgets invisibly.
  New UI uses these; do not add raw `Color.argb(...)` backgrounds.
 `TilePanel` is the docked bottom panel for a tapped tile (biome, `TileCoordinates`, up to four glyphs from `ResourceGlyphs`, the same art as the in-game icons). `SettingsSheet` is the settings bottom sheet (seed, density, drag flip, new world) built on `InkTheme.sheet`. `NewWorldFlow` asks before a world is replaced and covers the map with a stage screen while it is made (fed by `GlobeScreen.onGenerationStage`).
 A replacement runs on a worker thread (`GlobeScreen.regenerateWorld`, steps and cancel in `GenerationProgress`; the screen shows the share
 done, the time left and Cancel through `onGenerationProgress`). With room for the new caches beside the old (160 MB free; the old ones are already off that) the new
 world is written to `.new` siblings and swapped in once whole, so Cancel - or a kill - leaves the old world intact and it is reloaded from
 disk; without that room the old caches go first and there is no Cancel. A cancel is noticed at the end of a step, so it can take as long as
 the longest one. The step weights in `GenerationProgress.Step` were measured on the phone (re-measure with `adb logcat -s perf` if generation changes).