# josm_cog — instructions for agents

JOSM plugin displaying remote COGs (Cloud Optimized GeoTIFF) as background
layers, using HTTP byte-range reads only.

## Non-negotiable principles

- **Never download the whole file**: every read goes through
  `ByteSource`/`HttpRangeReader` (`Range:` requests). A server that does not
  support ranges must be rejected (`RangeNotSupportedException`).
- **No server proxy**: the plugin talks directly to the tile server.
- **No external dependency**: JDK + JOSM API only (`lib/josm-tested.jar`,
  provided by `./fetch-josm.sh`). No GDAL, no TIFF/JPEG library at runtime.
- License **GPL v2+** (like JOSM); do not add files under an incompatible
  license. No per-file license header (a root `LICENSE` is enough, follow the
  existing files).
- V1 accepts only **EPSG:3857 and EPSG:4326**. Since phase 2, per-tile
  reprojection is enabled for the CRSs listed in `crs/Crs` (4326, 3857, WGS84
  UTM 32601-32660 / 32701-32760, Lambert-93 2154); since phase 2.1 also
  **custom GeoKeys (32767)** whose parameters describe a known model
  (geographic, Transverse Mercator, Lambert conformal conic 1SP/2SP — see
  `Crs.fromCustom`). Any other CRS or method must raise an explicit error (no
  silent reprojection, no SRID guessing). No PROJ.4/WKT parsing: only GeoTIFF
  keys are read (assumed trade-off, phase 2.2 = proj4j).

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
./gradlew build          # jar + tests -> build/libs/cog.jar
./gradlew test           # tests only (JUnit 5)
```

- **Always check the "BUILD SUCCESSFUL" line** in the full output. Never
  write `./gradlew build 2>&1 | tail -1 && cp …`: `tail -1` can hide a
  compile failure (the "1 actionable task" message) and `cp` would deploy a
  stale jar.
- Java 17 target (`options.release = 17`): do not use a more recent API.

## Useful gradle tasks

- `./gradlew spike --args="https://example.tif"`: diagnostic CLI, prints the
  pyramid/extent/SRID without JOSM (run it first on any new COG).
- `./gradlew spike --args="https://example.tif --tile LEVEL TX TY out.png"`:
  decodes one native tile to PNG.
- `./gradlew serve-cog --args=". 8765"`: `CogFileServer` (test tool), JDK
  static server with byte-ranges. Local example:
  `http://127.0.0.1:8765/cog-paris-3857.tif`.

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
  cache (`MetadataCachingSource`) and the direct source; never cache pixels.
- `layer/`: JOSM integration. `CogImageryLayer` extends
  `AbstractCachedTileSourceLayer<CogTileSource>`; `CogTileSource` is a TMS
  source without URL (on-the-fly synthesis) that **carries the `CogReader`**;
  `CogTileLoaderJob` retrieves the reader via `tile.getSource()`.
- `AddCogLayerAction`: URL dialog + open + zoom to the extent.
- `crs/`: in-house projections (Snyder / USGS PP 1312, sub-mm accuracy in the
  forward direction, validated against GDAL/PROJ in `ProjectionTest`).
  `Projection` interface (project/unproject), registry/cache `Crs.forEpsg`.
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
  3075=14). Albers (3075=11) is NOT supported: it must be rejected
  explicitly (fixture `custom_albers_cog.tif`).
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

## Reference COG example

`cog-paris-3857.tif` (root, 1.4 MB, EPSG:3857) and `cog-paris-l93.tif`
(750 KB, EPSG:2154, same scene). Regeneration:

```sh
gdalwarp -t_srs EPSG:3857 -te_srs EPSG:4326 -te 2.25 48.80 2.45 48.92 \
  -tr 10 10 -r bilinear -of COG -co COMPRESS=JPEG -co QUALITY=85 \
  "/vsicurl/https://sentinel-cogs.s3.us-west-2.amazonaws.com/sentinel-s2-l2a-cogs/31/U/DQ/2020/6/S2A_31UDQ_20200629_1_L2A/TCI.tif" \
  cog-paris-3857.tif
gdalwarp -t_srs EPSG:2154 -te_srs EPSG:4326 -te 2.25 48.80 2.45 48.92 \
  -tr 10 10 -r bilinear -of COG -co COMPRESS=JPEG -co QUALITY=85 \
  "/vsicurl/https://sentinel-cogs.s3.us-west-2.amazonaws.com/sentinel-s2-l2a-cogs/31/U/DQ/2020/6/S2A_31UDQ_20200629_1_L2A/TCI.tif" \
  cog-paris-l93.tif
```

It is the only "realistic" COG format covered by the tests (JPEG with
external tables); rasterio COGs (tables in the stream) are also accepted.

### "Custom GeoKeys" fixtures (phase 2.1)

`custom_lcc_cog.tif`, `custom_tmerc_cog.tif`, `custom_longlat_cog.tif`
(root, generated by GDAL, hence realistic GeoKeys 32767 + 34736 doubles) and
`custom_albers_cog.tif` (method 3075=11, must be **rejected**). GDAL
reference anchors in `CogCustomWarpTest`/`CustomCrsTest`. Regeneration:

```sh
gdalwarp -q -of GTiff -tr 100 100 -t_srs "+proj=lcc +lat_1=44 +lat_2=49 +lat_0=46.5 +lon_0=3 +k_0=0.9999 +x_0=700000 +y_0=6600000 +ellps=GRS80 +units=m +no_defs" \
  -te 645000 6855000 650000 6860000 cog-paris-l93.tif custom_lcc.tif
gdalwarp -q -of GTiff -tr 100 100 -t_srs "+proj=tmerc +lat_0=48.85 +lon_0=2.35 +k=0.99986521 +x_0=600000 +y_0=2200000 +ellps=clrk80ign +units=m +no_defs" \
  -te 598000 2198000 602000 2202000 cog-paris-l93.tif custom_tmerc.tif
gdalwarp -q -of GTiff -t_srs "+proj=longlat +ellps=clrk80ign +no_defs" cog-paris-l93.tif custom_longlat.tif
gdalwarp -q -of GTiff -tr 100 100 -t_srs "+proj=aea +lat_1=42 +lat_2=44 +lat_0=46 +lon_0=3 +x_0=1000000 +y_0=6000000 +ellps=GRS80 +units=m +no_defs" \
  cog-paris-l93.tif custom_albers.tif
for f in custom_lcc custom_tmerc custom_longlat custom_albers; do
  gdal_translate -q -of COG -co COMPRESS=DEFLATE $f.tif ${f}_cog.tif; done
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
  `java -cp /usr/share/josm/josm.jar:build/libs/cog.jar:/tmp/opencode
  LinkCheck build/libs/cog.jar` (the jar under test must ALSO be in the
  classpath).

## Roadmap

Phase 2 (done): per-tile reprojection (UTM, Lambert-93) through the in-house
`crs/` formulas (no full PRJ).
Phase 2.1 (done): custom GeoKeys 32767 mapped onto the existing `crs/`
classes (`Crs.fromCustom`), no new projection math.
Phase 2.2 (considered): proj4j backend for models not covered (Albers,
oblique Mercator, non-WGS84 datums…) — it would break the "no dependency"
constraint, to be arbitrated.
Phase 3: publication on the JOSM plugin list (requires an up-to-date
`Plugin-Mainversion` and JOSM javadoc/checkstyle if upstreamed).
