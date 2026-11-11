# josm_cog — instructions for agents

JOSM plugin displaying remote COGs (Cloud Optimized GeoTIFF) as background
layers, using HTTP byte-range reads only.

## Non-negotiable principles

- **Never download the whole file**: every read goes through
  `ByteSource`/`HttpRangeReader` (`Range:` requests). A server that does not
  support ranges must be rejected (`RangeNotSupportedException`).
- **No server proxy**: the plugin talks directly to the tile server.
- **No external dependency** (restored in phase 2.4): JDK + JOSM API only,
  the plugin jar contains nothing but the plugin's classes. No GDAL, no
  TIFF/JPEG library at runtime, and no embedded third-party code at all -
  the project ships under pure **GPL v2+** (the proj4j fallback added in
  phase 2.2 was removed: Apache-2.0 is incompatible with GPL v2). NEVER
  add proj4j or `proj4j-epsg` back without explicit user arbitration; the
  EPSG database bundled in proj4j-epsg additionally has restrictive terms
  (Apache CALCITE-5399) and CRS parameters must keep coming from the
  file's GeoKeys or the host projection table, never from an EPSG lookup.
- License **GPL v2+** (like JOSM); do not add files under an incompatible
  license. No per-file license header (a root `LICENSE` is enough, follow the
  existing files).
- V1 accepts only **EPSG:3857 and EPSG:4326**. Since phase 2, per-tile
  reprojection is enabled for the CRSs computed by the in-house `crs/`
  classes; since phase 2.1 also **custom GeoKeys (32767)** whose
  parameters describe a known model (geographic, Transverse Mercator,
  Lambert conformal conic 1SP/2SP — see `Crs.fromCustom`); since phase
  2.3 the hand-written EPSG registry is **replaced** by the projection
  table shipped by the host application: built-ins stay 4326/3857 only,
  every other code is looked up in JOSM's `data/projection/custom-epsg`
  (resource read through the classloader, `crs/JosmEpsgTable`) when its
  model is on the whitelist (longlat, tmerc, utm, lcc). CRS parameters
  therefore come from exactly three sources: the built-ins, the file's
  GeoKeys, or the host table — the plugin embeds and downloads **no EPSG
  database of its own**. Any other CRS or method must raise an explicit
  error (no silent reprojection, no SRID guessing); the models served by
  the former proj4j fallback (aea, laea, stere and the GeoKeys methods
  10/11/28/14-stere/15/9815) are back to explicit refusal (phase 2.4).
  No PROJ.4/WKT parsing from COG files: files carry only GeoTIFF keys;
  the host's own table is the second (user-approved) source, its PROJ
  strings are parsed token by token and never fed raw to a library — the
  parameters only reach the plugin's own projection classes.

## Language and style

- **Source-code comments and messages in ENGLISH** (javadoc, inline comments,
  exception messages, logs). Strings intended for the JOSM GUI (dialogs,
  tooltips) are in English **and wrapped in `tr()`**: the French version comes
  from JOSM i18n, not from the code. No accented letter nor non-ASCII sign in
  the sources (except the "…" in `tr()` strings, JOSM convention).
- **Commit messages in French without accents.** AGENTS.md and README.md are
  in English; README_fr.md is the French twin of README.md (the only file
  allowed to keep accents).
- **Keep the two READMEs in sync:** any change to README.md must be mirrored
  in README_fr.md in the same commit (and the other way around) — identical
  structure, tables, commands and examples; only the prose differs.
- Identifiers and API in English.
- Imports at the top of files, no wildcard imports; JOSM `Logging` for all
  logging, messages prefixed "josm-cog:".

## Build / test

```sh
export JAVA_HOME=~/tools/jdk-21.0.13+9   # local JDK 21 (JDK 17+ required)
./fetch-josm.sh          # if lib/josm-tested.jar is missing
./gradlew build          # jar + tests -> build/libs/cog4josm.jar
./gradlew test           # tests only (JUnit 5)
```

- **Always check the "BUILD SUCCESSFUL" line** in the full output. Never
  write `./gradlew build 2>&1 | tail -1 && cp …`: `tail -1` can hide a
  compile failure (the "1 actionable task" message) and `cp` would deploy a
  stale jar.
- Java 17 target (`options.release = 17`): do not use a more recent API.
- `lib/josm-tested.jar` is `compileOnly` (never bundled) and also
  `testImplementation`: the CRS tests read the projection table resource
  `/data/projection/custom-epsg` from it (see the phase 2.3 pitfalls).

## Useful gradle tasks

- `./gradlew spike --args="https://example.tif"`: diagnostic CLI, prints the
  pyramid/extent/SRID without JOSM (run it first on any new COG).
- `./gradlew spike --args="https://example.tif --tile LEVEL TX TY out.png"`:
  decodes one native tile to PNG.
- `./gradlew serve-cog --args=". 8765"`: `CogFileServer` (test tool), JDK
  static server with byte-ranges. Local example:
  `http://127.0.0.1:8765/test_data/cog-paris-3857.tif` (all fixtures live
  in `test_data/`).

## Architecture (packages under `org.openstreetmap.josm.plugins.cog`)

- `reader/`: JOSM-independent core (testable without GUI).
  `HttpRangeReader` (HTTP ByteSource) → `TiffParser` (IFD, GeoKeys) →
  `CogInfo` (pyramid + georeferencing) → `CogReader` (decoded tiles, LRU
  cache) → `CogComposer` (recomposition of 256 px OSM tiles) and
  `TileDecoder` (NONE/Deflate/PackBits/LZW/JPEG). `CustomCrs` carries the raw
  GeoKeys (including 34736 doubles) when the SRID is 0 or 32767;
  `CogTileSource` resolves the projection as soon as it receives the
  `CogInfo` and keeps the error to display it at tile load time.
- **I/O strategy** (`reader/`): `RangePlanner` merges small byte-ranges into
  few larger requests (external IFD tag values fetched in one block per IFD;
  adjacent COG tiles of a window fetched one block per row — they are stored
  row-major). `MetadataCache` (global LRU, keyed by URL + exact range,
  validated by size/ETag fingerprint at open) caches **metadata only** —
  header, IFDs, tile offset arrays — never raw raster: tiles are fetched
  coalesced and kept only as decoded images (`CogReader` cache).
  Rule: any new read path must choose deliberately between the metadata
  cache (`MetadataCachingSource`) and the direct source; never cache pixels
  **in the reader**. Composed 256 px display tiles are a different thing:
  they go into JOSM's own imagery tile cache (see `layer/` below), never
  raw COG bytes.
- `layer/`: JOSM integration. `CogImageryLayer` extends
  `AbstractCachedTileSourceLayer<CogTileSource>`; `CogTileSource` is a TMS
  source without URL (on-the-fly synthesis) that **carries the `CogReader`**;
  `CogTileLoaderJob` retrieves the reader via `tile.getSource()`. Since
  phase 2.5 the composed tiles are kept in the layer's JCS region "cog"
  (`CogImageryLayer.tileCacheRegion()` = `AbstractCachedTileSourceLayer
  .getCache(name)`, the very region the JOSM host uses for TMS layers:
  memory + disk spool + purge actions for free). Key =
  `fingerprint/zoom/x/y` where the fingerprint (computed once per
  `CogTileSource`) is SHA-256(url + size + ETag) first 16 hex chars — the
  same identity trio as `MetadataCache.validate`, so a changed remote file
  never serves stale tiles. Entries are `BufferedImageCacheEntry
  .pngEncoded(img)` (the only serializable form; the decoded image is
  transient). Never cached: error tiles, CRS-error tiles, tiles that miss
  the COG (compose returns null) or come out fully transparent
  (`CogTileLoaderJob.isBlank`); a cache failure never fails the tile.
- `AddCogLayerAction`: URL dialog + open + zoom to the extent.
- `crs/`: in-house projections (Snyder / USGS PP 1312, sub-mm accuracy in the
  forward direction, validated against GDAL/PROJ in `ProjectionTest`).
  `Projection` interface (project/unproject), registry/cache `Crs.forEpsg`
  (built-ins 4326/3857 + host-table lookup through `JosmEpsgTable`).
  `CogComposer.composeWarp`: 9x9 control grid projected exactly (3857 ->
  lat/lon -> model coordinates), bilinear interpolation per output pixel,
  alpha-aware ARGB bilinear sampling. The linear 3857/4326 paths of `compose`
  are kept (non-regression).
- `tools/CogFileServer`: tests only (must not be linked into the plugin).

## TIFF/COG pitfalls (all already hit — do not regress)

- **libtiff** numbering of tile tags: 322 TileWidth, 323 TileLength,
  324 TileOffsets, 325 TileByteCounts. The **JPEGTables tag is 347**
  (530 = YCbCrSubsampling, not JPEG tables!).
- **JPEG tiles without embedded tables**: GDAL (COG driver) puts the DQT
  tables in tag 347, not in the stream. `TileDecoder.withJpegTables`
  re-injects them when the stream has no DQT before SOS. Test fixture:
  `src/test/resources/gdal-jpeg-{tables,tile}.bin`.
- **Spherical Mercator** (OSM/JOSM/JMapViewer grid): R = 6378137,
  `HALF_CIRCUMFERENCE = π·R = 20,037,508.34` (the widely circulated
  "20037504.17" value is wrong). The latToY/yToLat formulas must use R, not
  π·R. A round-trip alone does not detect a symmetric error: the tests contain
  **absolute control values**, keep them.
- **Pyramid sort computed as int**: a level's `width*height` area overflows
  `int` from 2^31 pixels (PIX4Dmatic COG 131072x98304 = 3x2^32 → area 0, and
  the overview 65536x49152 = 3x2^30 → negative): overviews came before level 0
  and the `flags&1` guard wrongly raised "Missing full resolution IFD" on
  perfectly valid files. Use `Long.compare`
  (`TiffParser.sortLevelsByAreaDesc`, dedicated non-regression test).
- **BigTIFF (magic 43)** is not classic TIFF stretched: header is 16 bytes
  (offset size + constant), entry counts and next-IFD are uint64, entries are
  **20 bytes** (count uint64, value field 8 bytes, inline up to 8), types
  LONG8/SLONG8/IFD8 = 16/17/18. The 64-bit entry count is hostile: capped by
  `MAX_IFD_ENTRIES`.
- **GDAL alpha COGs interleave a mask IFD chain**: one RGB (or grey) IFD per
  level followed by a transparency mask IFD (photometric=4 MinIsMask, often
  1-bit Deflate — `toImage` cannot even size it: 512x512x1bit = 32768 bytes).
  Counting them as levels doubles the pyramid and swaps RGB for mask at
  alternating zooms. `toLevel` skips them (photometric 4 / NewSubfileType
  bit 4); real alpha compositing is a later phase. Real sample: PIX4Dmatic
  `Anglet2025_COG.tif` (3.8 GB, BigTIFF, EPSG:3943).
- **`getRGB()` on `TYPE_BYTE_GRAY` is ICC transformed** (sample 10 comes back
  as 0x38 under the default gray profile): the plugin consumers use the
  raster, and tests on grey tiles must assert `getRaster().getSample`, never
  `getRGB`.

## JOSM API pitfalls (validated on r19678, `lib/josm-tested.jar`)

- **Jar manifest**: only the official names `Plugin-Class`,
  `Plugin-Mainversion`, `Plugin-Minimum-Java-Version`. Dash-variant names like
  `Plugin-Main-Version` are silently ignored → the plugin does not load, JOSM
  only shows a "Plugin main version missing" warning.
- `AbstractCachedTileSourceLayer` builds
  `CachedTileLoaderFactory(getCache(), getTileLoaderClass())` **by
  reflection** in its constructor: `CogTileLoader` MUST have a public
  `(TileLoaderListener, ICacheAccess, TileJobOptions)` constructor even if
  cache/options are unused. Do not pass state through this constructor (hence
  the reader carried by `CogTileSource`).
- `ImageryInfo` rejects unknown types: do not do
  `new ImageryInfo(name, url, "cog", …)`, use the default constructor.
- `Logging.warn(msg, args)` applies `MessageFormat`: apostrophes in a message
  containing `{0}` break the formatting (raw message displayed, exception
  lost). Write JOSM messages without apostrophes or pass them through
  `Logging.warn(exception)` separately.
- `MapView.zoomTo(Bounds)` is inherited from `NavigatableComponent`; after
  `addLayer`, the map frame may still be under construction: the zoom is
  deferred with a `javax.swing.Timer` (see `AddCogLayerAction`).
- Plugin lifecycle logging: `mapFrameInitialized` may miss an already open
  window → the action is added to the menu with retry.

## Reprojection pitfalls (phase 2, already hit)

- `LambertConformalConic2SP`: the constructor parameter `lat0` shadows the
  radians field `this.lat0`; use `t(this.lat0)` for `rho0` (otherwise NaN).
- GeoTIFF convention: pixel k covers [k, k+1[ and its CENTER is k+0.5
  (`col = (x - originX) / scaleX`, sample at `col - 0.5`). A test pattern
  centered on integers produces a systematic half-pixel shift.
- Row 0 of a Java image = North = `bbox.maxY`: the warp control grid must
  start at `maxY` (otherwise the output is vertically flipped; a test that
  reproduces the same wrong convention will not catch it).
- GDAL Python references:
  `srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)`
  (method `SetAxisMappingStrategy`, not `SetAxisMapping`) to get lon/lat
  order, otherwise the transforms are wrong.
- Snyder 6th-order series: ~3 mm error at 5 degrees from the central meridian
  (test tolerances widened for cases outside a UTM zone).

## Custom GeoKeys pitfalls (phase 2.1, already hit)

- **Scope of the `crs/` constructors**: a "degrees" parameter carrying the
  same name as a radians field (`lat0`, `lon0`…) shadows that field; the
  LCC/TM projections were wrong because of this. Convention: parameters
  suffixed `Deg` and explicit `this.` everywhere.
- **3074/3075 reconciliation**: GeoKey 3074 (ProjCoordTrans) = 32767 with
  GDAL; the real method code is in 3075. The values TM=1 and LCC2SP=8 are
  shared between both tables; only the Lambert 1SP codes differ (3074=9 vs
  3075=14). Albers (3075=11) went through the proj4j fallback in phases
  2.2-2.3 and is refused again since 2.4 (fixture
  `test_data/custom_albers_cog.tif`
  kept as explicit-refusal regression).
- **PROJ convention for the Lambert scale factor**: GeoKey 3092 (k0) missing
  means 1.0; when present, PROJ applies it to the WHOLE rho radius (not only
  rho0), so (lat0,lon0) falls exactly on (FE,FN) and the local scale at lat0
  is k0. `LambertConformalConic2SP` does the same (verified against GDAL:
  1SP k_0=0.99987731, (45N,5E) -> (1000000,2000000)).
- **Ellipsoid**: prefer doubles 2057 (semi-major axis) / 2059 (inverse
  flattening), otherwise code 2056 (table `Ellipsoid.forGeoKeyCode`); prime
  meridian 2061 (double) supported in geographic mode only; linear units 3076
  != 9001 via the `Crs$Scaled` adapter; angular units != degrees rejected.
- **Test writer `TestTiffWriter`**: `geoBlockSize()` must return the size
  EXACTLY written by `writeExt` (72 + 2*nKeys + 8*nDbl + CITATION+1 in custom
  mode, 96 otherwise) — a 7-byte shift misplaces every TileOffsets (symptom:
  "Expected tile size N bytes, received 0"). GeoKeys are `int[]` (the 34736
  locator exceeds signed short) and `write()` checks that the header
  NumberOfKeys matches exactly the present entries.
- COGs written by GDAL in custom mode can have 2061=0 even when lon_0 was
  non-zero in the generating PROJ string: trust the GeoKeys read, not the
  generation intent (longlat fixture case).
- **"Registered code + parameters" hybrid** (PIX4Dmatic exports, e.g.
  COG_BayonneSudEst.tif in EPSG:3943/Lambert-43): 3072 carries an EPSG code
  known to GDAL but unknown to our registry, and 3074=32767 guarantees that
  ALL method parameters are present as DOUBLEs. Rule: `TiffParser` keeps the
  `CustomCrs` as soon as 3074=32767, and `Crs.resolve(code, custom)` tries
  the EPSG registry first then the parameters (never the other way around, a
  registered code wins). Without 3074=32767, a registered 3072 stays rejected
  ("inconsistent custom keys"): no usable parameters. The test writer aligns
  the custom block on the PRESENCE of customKeys, not on the crs value
  (otherwise 2 missing IFD entries, a 24-byte shift of the whole geographic
  block).

## Proj4j fallback removed (phase 2.4, license purity)

- The proj4j Apache-2.0 core (embedded since phase 2.2) was incompatible
  with the project's **GPL v2+** licence: `crs/Proj4Backend`, the
  `implementation` dependency, `lib/proj4j-LICENSE.txt` and the
  class-embedding of the `jar` task are all deleted. Do NOT reintroduce
  any third-party runtime code without explicit user arbitration.
- Models now (or again) refused with an explicit reason: custom GeoKeys
  methods 10 (laea), 11 (aea), 28 (cea), 14 without standard parallels
  (oblique stereographic), 15 (polar stereographic), 9815 (Hotine/somerc),
  and the host-table models `aea`/`laea`/`stere` (EPSG:3035 was only
  supported during release 0.1.x - anchor rows removed from
  `ProjectionTest.TABLE_ANCHORS`).
- Knowledge measured in phase 2.2 is kept for a future clean-room re-
  implementation: proj4j parses `+rf`/`+f` differently from PROJ (b ends
  up ~1.9e9 m); its aea inverse drifts by degrees on a sphere; polar
  stereographic without lat_ts gives 97 540 m where PROJ gives
  194 429 m; eqdc 13, merc+lat_ts 7, poly 22, ortho 21 and the general
  Hotine drift by metres to kilometres against PROJ.
- **Dual 3075 code tables** (still relevant, `Crs.resolveMethod`):
  libgeotiff and GDAL custom mode do not use the same method codes in
  3075: tmerc=1, merc=7, lcc2sp=8, laea=10, aea=11, eqdc=13, stere=14,
  polar=15, ortho=21, poly=22, cea=28, Hotine/somerc=9815. Value 14
  collides with libgeotiff's Lambert 1SP: disambiguate by the presence of
  standard parallels 3078/3079 (present -> in-house Lambert 1SP class;
  absent -> oblique stereographic, now explicitly rejected).
- **Natural origin keys** (GDAL): azimuthal/stereographic centre in
  3088 (longitude) / 3089 (latitude), Hotine scale in 3093 (vs 3092
  elsewhere), polar centre longitude in 3095, Hotine azimuth and
  rectified-grid angle in 3094/3096; FE/FN stay in 3082/3083 (the
  3084-3087 false-origin group is an accepted fallback). Documented in
  `CustomCrs` for error messages; nothing computes with them today.

## Display tile cache pitfalls (phase 2.5, measured on r19678)

- **`Tile` constructor argument order**: `org.openstreetmap.gui.jmapviewer.Tile
  (source, x, y, zoom)` — NOT `(source, zoom, x, y)`. Swapped, the zoom
  becomes a tile index: `MercatorTiles.tileBounds` divides by
  `Math.pow(2, huge)` = Infinity, returns a degenerate box and
  `CogComposer.compose` quietly returns null (this trap cost a debugging
  session when the first cache tests were written).
- `Tile.getImage()` is a plain field read, and the 4-arg constructor sets
  `image = LOADING_IMAGE` (a 255x255 ARGB placeholder): a loaded tile whose
  composition returned null still shows a non-null placeholder — never
  infer "has content" from `getImage() != null`.
- `AbstractCachedTileSourceLayer` has a **public static `getCache(String)`**
  that returns the very JCS region its private instance `getCache()` uses
  (both route to `JCSCacheManager.getCache(name, MAX_DISK_CACHE_SIZE...)`):
  that is what `CogImageryLayer.tileCacheRegion()` calls. Standalone probes
  must first `Config.setPreferencesInstance(new MemoryPreferences())` and
  `Config.setBaseDirectoriesProvider(...)` or class init NPEs.
- JCS3 signatures to respect when faking `ICacheAccess` with a
  `java.lang.reflect.Proxy` (the pattern used by `CogTileCacheTest`):
  `ICache.put(K,V)` returns **boolean** (returning null through the proxy
  raises a swallowed ClassCastException — silent missing puts),
  `CacheAccess.remove(K)` returns void. Only `get`/`put`/`remove` are ever
  called by the plugin.
- Never cache: error tiles, CRS-error tiles, tiles whose composition is
  null (miss the COG) or fully transparent (`CogTileLoaderJob.isBlank`);
  all cache exceptions are swallowed — a cache failure must never fail a
  good tile. A corrupt entry read back is treated as a miss and removed.
- Persistable form: `BufferedImageCacheEntry.pngEncoded(img)`; the decoded
  image is transient, so the JOSM disk spool (`tiles` dir) stays safe;
  re-decode via `getImage()` on hit. PNG is lossless: round-trip is
  pixel-identical (asserted in `CogTileCacheTest`).

## Host EPSG table pitfalls (phase 2.3, measured against PROJ 9.9)

- **Linear units in PROJ strings**: `+x_0`/`+y_0` are metres but the whole
  output is then divided by the `+units`/`+to_meter` factor
  (`+x_0=500000 +units=ft` outputs 1,640,419.95 ft). `JosmEpsgTable`
  feeds the raw offsets to the metre-based constructors and a `Scaled`
  wrapper expresses the output in the declared unit: do NOT "fix" this by
  multiplying the offsets by the factor.
- **Same semantics for the GeoKeys path** (bug fixed in 2.3): false
  easting/northing are declared in the GeoKey 3076 linear unit and the
  output coordinates are in that unit — the in-house constructors receive
  the offsets multiplied by `unitFactor` (metres), the `Crs$Scaled`
  wrapper expresses the output back in the declared unit;
  `CustomCrsTest.linearUnitsScaled` expects FE/FN verbatim.
- **Token allow-list per model** (`MODEL_TOKENS`): unknown, misplaced or
  duplicated tokens are rejected; `+k` xor `+k_0`; `+ellps` xor `+datum`
  xor a/b/rf; `+units` xor `+to_meter`; unknown `+axis`, named `+pm`,
  `+vunits` and unknown unit names are rejected (never approximated).
  `towgs84`/`nadgrids` are ignored (datum first order, as elsewhere).
- **Ellipsoid/datum name tables** are validated against PROJ: `helmert`
  = rf 298.3 (PROJ alias of EPSG:7020 — WGS84's rf is centimetres off at
  UTM scales), `+datum=ire65` implies `mod_airy` (with `airy` the error
  is 0.8 m), `carthage` implies `clrk80ign`. Entries 29901/29902/29903
  coincide within microns (k compensates the ellipsoid) yet the 29902
  anchor still catches a wrong ire65 mapping.
- **DMS spellings** `52d9'22.178"`, `0dE`, `96dW`: the trailing `"`
  terminates the seconds, it introduces no second value (an earlier
  regex read a phantom sub-seconds group).
- **Polar stereographic with `+k` only, no `+lat_ts`** (ESRI entries
  102018/102021): proj4j and PROJ used different radii (97,540 m vs
  194,429 m). Moot since 2.4 - `stere` is refused at model level - but
  keep the number: any future re-implementation must not use the
  k-only pole form without re-deriving it from PROJ.
- **`LambertConformalConic2SP` southern-hemisphere inverse**: for n < 0,
  rho < 0 and (dx, dy) = rho * (sin theta, cos theta), so theta must be
  `atan2(rhoSign*dx, rhoSign*dy)` — applying the sign to dx alone
  reflects theta across the cone axis (caught by the EPSG:3110 anchor).
- **Anchor recipe** (`ProjectionTest.TABLE_ANCHORS`, tolerance 1 mm in
  the entry's own unit): `gdaltransform -s_srs EPSG:4326 -t_srs "<entry>"`
  with the entry sanitized as: drop `+towgs84`/`+nadgrids`/`+vunits`,
  keep `+units`/`+to_meter`, replace `+datum=NAME` by the `+ellps=` that
  the PROJ datum table implies.
- **Test classpath**: `build.gradle` carries
  `testImplementation files('lib/josm-tested.jar')` so tests see the
  host resource `/data/projection/custom-epsg` (6135 entries, first
  entry wins). NEVER move that line to `implementation` — the plugin jar
  must not embed JOSM classes (keep checking LinkCheck: ko=0).
- **Behaviour changes vs the old hand registry**: the explicit-refusal
  tests moved to 2172 (sterea), 53004 (`merc` — "Mercator is not taken
  from the table", 3857/4326 stay built-in), 23700 (`somerc`), 102018
  (`stere`, model-level refusal since 2.4); EPSG:3035 (laea) was
  supported during phase 2.3 only and is refused again.

## Reference COG example

`test_data/cog-paris-3857.tif` (1.4 MB, EPSG:3857) and
`test_data/cog-paris-l93.tif` (750 KB, EPSG:2154, same scene). All COG
fixtures live in `test_data/`; the regeneration commands below run from
the repository root. Regeneration:

```sh
gdalwarp -t_srs EPSG:3857 -te_srs EPSG:4326 -te 2.25 48.80 2.45 48.92 \
  -tr 10 10 -r bilinear -of COG -co COMPRESS=JPEG -co QUALITY=85 \
  "/vsicurl/https://sentinel-cogs.s3.us-west-2.amazonaws.com/sentinel-s2-l2a-cogs/31/U/DQ/2020/6/S2A_31UDQ_20200629_1_L2A/TCI.tif" \
  test_data/cog-paris-3857.tif
gdalwarp -t_srs EPSG:2154 -te_srs EPSG:4326 -te 2.25 48.80 2.45 48.92 \
  -tr 10 10 -r bilinear -of COG -co COMPRESS=JPEG -co QUALITY=85 \
  "/vsicurl/https://sentinel-cogs.s3.us-west-2.amazonaws.com/sentinel-s2-l2a-cogs/31/U/DQ/2020/6/S2A_31UDQ_20200629_1_L2A/TCI.tif" \
  test_data/cog-paris-l93.tif
```

It is the only "realistic" COG format covered by the tests (JPEG with
external tables); rasterio COGs (tables in the stream) are also accepted.

### "Custom GeoKeys" fixtures (phase 2.1)

`test_data/custom_lcc_cog.tif`, `test_data/custom_tmerc_cog.tif`,
`test_data/custom_longlat_cog.tif` (generated by GDAL, hence realistic
GeoKeys 32767 + 34736 doubles) and
`test_data/custom_albers_cog.tif` (method 3075=11, regression case for the explicit
refusal since phase 2.4: the spike must report it, never load it). GDAL
reference anchors in `CogCustomWarpTest`/`CustomCrsTest`. Regeneration:

```sh
gdalwarp -q -of GTiff -tr 100 100 -t_srs "+proj=lcc +lat_1=44 +lat_2=49 +lat_0=46.5 +lon_0=3 +k_0=0.9999 +x_0=700000 +y_0=6600000 +ellps=GRS80 +units=m +no_defs" \
  -te 645000 6855000 650000 6860000 test_data/cog-paris-l93.tif test_data/custom_lcc.tif
gdalwarp -q -of GTiff -tr 100 100 -t_srs "+proj=tmerc +lat_0=48.85 +lon_0=2.35 +k=0.99986521 +x_0=600000 +y_0=2200000 +ellps=clrk80ign +units=m +no_defs" \
  -te 598000 2198000 602000 2202000 test_data/cog-paris-l93.tif test_data/custom_tmerc.tif
gdalwarp -q -of GTiff -t_srs "+proj=longlat +ellps=clrk80ign +no_defs" test_data/cog-paris-l93.tif test_data/custom_longlat.tif
gdalwarp -q -of GTiff -tr 100 100 -t_srs "+proj=aea +lat_1=42 +lat_2=44 +lat_0=46 +lon_0=3 +x_0=1000000 +y_0=6000000 +ellps=GRS80 +units=m +no_defs" \
  test_data/cog-paris-l93.tif test_data/custom_albers.tif
for f in custom_lcc custom_tmerc custom_longlat custom_albers; do
  gdal_translate -q -of COG -co COMPRESS=DEFLATE test_data/$f.tif test_data/${f}_cog.tif; done
```

## End-to-end GUI tests (this machine, optional)

First try to validate with `spike` + unit tests; the Xvfb GUI is the last
step. Known shortcuts:

- Xvfb: `/tmp/opencode/xvfbroot/usr/bin/Xvfb :7 …`; xdotool:
  `/tmp/opencode/xdotooloroot/usr/bin/xdotool` with
  `LD_LIBRARY_PATH=/tmp/opencode/xdotooloroot/usr/lib/x86_64-linux-gnu`;
  capture: `DISPLAY=:7 import -window root`.
- JOSM launch: same `--add-exports` as the Debian JOSM plugin +
  `-Djosm.home=/tmp/opencode/josmhome -Djosm.plugins=cog -Dimagery.proj=EPSG:3857`
  + `--offline=ALL --debug` (debug logs `tileLoadingFinished`).
- `pkill -f` kills its own shell when the pattern matches the command line:
  write the pattern in pieces (`"josm-tes""ted.jar"`).
- GUI-free link verification: `/tmp/opencode/LinkCheck.class`, run
  `java -cp /usr/share/josm/josm.jar:build/libs/cog4josm.jar:/tmp/opencode
  LinkCheck build/libs/cog4josm.jar` (the jar under test must ALSO be in the
  classpath).

## Roadmap

Phase 2 (done): per-tile reprojection (UTM, Lambert-93) through the in-house
`crs/` formulas (no full PRJ).
Phase 2.1 (done): custom GeoKeys 32767 mapped onto the existing `crs/`
classes (`Crs.fromCustom`), no new projection math.
Phase 2.2 (withdrawn by phase 2.4): proj4j fallback (`crs/Proj4Backend`) for
models missing from the in-house `crs/` classes — the empirically verified
subset only. Removed for licence purity (Apache-2.0 inside a GPL v2+ jar).
Phase 2.3 (done): the hand-written EPSG code registry is replaced by the
projection table shipped by the host application — `crs/JosmEpsgTable`
reads JOSM's `data/projection/custom-epsg` at runtime (user-approved: the
plugin itself still embeds/downloads no EPSG database, `proj4j-epsg`
stays forbidden). Built-ins are 4326/3857; other codes resolve when the
entry's model is on the whitelist (longlat, tmerc, utm, lcc); anything
else keeps an explicit refusal reason.
Phase 2.4 (done): proj4j dependency and embedded classes removed, project
back under pure GPL v2+ (no third-party code in the jar); models aea/laea/
stere (table) and GeoKeys methods 10/11/28/14-stere/15/9815 refused with
an explicit reason.
Phase 2.5 (done): composed display tiles are kept in JOSM's own imagery
tile cache — JCS region "cog" injected into `CogTileLoader`, key
fingerprint(url+size+etag)+"/z/x/y", PNG-encoded `BufferedImageCacheEntry`
(memory + disk spool, JOSM purge actions apply); error/blank tiles never
cached. Raw COG bytes remain outside any cache (principle unchanged).
Phase 3: publication on the JOSM plugin list (requires an up-to-date
`Plugin-Mainversion` and JOSM javadoc/checkstyle if upstreamed).
