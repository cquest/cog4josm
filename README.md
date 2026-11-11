# cog4josm

(French version: [README_fr.md](README_fr.md))

> Note from the vibe-coder :
> I vibe coded this project as an experimentation of local Qwen3.8-flash-next coupled with opencode.
> It is here under GPL license to conform to JOSM license, but would have more naturally let it under WTFPL.
> I do not plan to maintain it, but as it works and can be useful, I prefer to share it.

JOSM plugin displaying remote **COG ([Cloud Optimized GeoTIFF](https://cogeo.org/))** imagery as
background layers, read **through HTTP byte-ranges**: the whole file is
never downloaded, only the needed TIFF tiles are fetched (and memory cached).

## Principle

```
HTTP Range ──► TiffParser ──► CogInfo (pyramid + georeferencing)
                                  │
JOSM 256px tile ──► CogComposer ──┤  picks the adequate pyramid level
                   (recomposition)◄── CogReader (decoded TIFF tiles)
```

* The plugin hooks into the standard JOSM imagery chain
  (`AbstractCachedTileSourceLayer` + dedicated `TileLoader`): cache, zoom,
  transparency and imagery filters work as is.
* Display tiles (OSM 256 px grid) are **rebuilt** on the fly from the COG
  native tiles (256/512/1024 px), whatever the offset of the georeferenced
  origin.
* Built-in TIFF decoders: none (1), Deflate (8/32946), PackBits (32773),
  LZW (5), JPEG (6, via ImageIO). Gray 8/16 bits, RGB(A), horizontal predictor.
* Coalesced requests: small ranges (one IFD's tags, neighbouring tiles of a
  window) are merged into a few `Range:` requests per block, following GDAL's
  overlapped-I/O model — about 2 to 4 requests per display tile instead of
  one per source tile.
* Speculative padding: every fetch window is ringed by one extra row/column
  of source tiles (decoded into the tile LRU, not part of the result), and
  the base overview is pre-fetched in the background at layer load when it
  is under 2 MiB — panning to neighbouring display tiles usually costs no
  request at all (measured: 20 → 12 requests for a 90-tile sweep, bytes
  flat).
* Speculative head read: opening an HTTP source issues a single
  `bytes=0-32767` request which proves `Range:` support (no separate probe
  request), reveals size and ETag, and carries the header, IFDs and GeoKeys
  of a well-laid-out COG in one round-trip. A header-tail COG (GDAL's
  default layout: IFDs at the very end) gets one more speculative request
  covering the last 32 KiB — two requests per open in every layout, never
  one per IFD.
* Lazy per-level tile tables: a level's TileOffsets/TileByteCounts table
  over 4 KiB is not fetched at open but when that level is first browsed
  — exploring a deep pyramid at overview zooms never downloads the
  full-resolution tables (on a 5-level fixture: 178 KiB of tables stay
  on the server until their levels are used).
* LRU cache of **metadata only** (header, IFDs, tile offset tables),
  validated by size/ETag and looked up by containment (a bigger cached block
  serves smaller reads): reopening the same COG costs that single head
  request. Raster content is never cached as raw bytes (only as decoded
  images).
* Metadata also survives JOSM restarts: blocks persist in one small file
  per URL under the plugin cache directory, and each open revalidates with
  a conditional `If-None-Match` request — if the file did not change, the
  answer is 304 and the metadata costs **zero bytes** over the wire
  (measured: reopened COG goes from 32 KiB to 0 bytes for one request).
  A changed size/ETag drops both memory and disk blocks before re-reading.
* The composed 256 px display tiles go into **JOSM's own imagery tile
  cache** (the "cog" JCS region: JOSM memory management and disk tile
  cache, purged by the usual JOSM actions). Keys combine a fingerprint of
  the source (URL + size + ETag), the transparency policy and z/x/y, so a
  changed file never serves stale tiles; error and empty tiles are never
  cached.
* Black-to-alpha transparency: sources with RGB (or JPEG YCbCr) pixels and
  no alpha channel get their near-black pixels rendered transparent —
  channels ≤ 8 by default (absorbs JPEG fringes), or only when the file
  declares `GDAL_NODATA` black. On by default; toggle per layer in its
  menu ("Transparency of black pixels"), or globally with the
  `cog.transparency` preference (`black_near` / `metadata` / `none`).
  Grey and alpha-carrying sources are never touched.
* Minimal dependencies: **JDK + JOSM API only**. The plugin jar contains no
  third-party code (GPL v2+ only). CRS parameters always come from the file
  itself (GeoKeys) or from the projection table shipped by JOSM — the
  plugin embeds and downloads no EPSG database of its own; models the
  plugin cannot compute are refused.

## Status (v0.5)

| Feature | Status |
|---|---|
| Byte-range reads, head + tail blocks at open (no probe) | ✅ |
| Tile tables fetched lazily, per level, on first use | ✅ |
| Black-to-alpha transparency (RGB sources without alpha) | ✅ (phase 3) |
| Small ranges merged into block requests | ✅ |
| Speculative tile ring + base overview prefetch | ✅ |
| Persistent metadata + conditional GET (304 on reopen) | ✅ |
| Metadata LRU cache (never raw raster) | ✅ |
| Composed display tiles in JOSM's tile cache (memory + disk) | ✅ |
| TIFF/GeoTIFF parser (IFD, sub-IFD, GeoKeys) | ✅ |
| BigTIFF container (64-bit offsets, PIX4Dmatic files) | ✅ |
| Interleaved alpha-mask IFDs (photometric 4) | detected, skipped (compositing later) |
| Multi-level pyramid, best level choice | ✅ |
| Web-Mercator tile recomposition | ✅ (EPSG:3857/4326) |
| Working JOSM layer (Imagery menu) | ✅ |
| Reprojection (UTM, Lambert-93) | ✅ |
| "Custom" GeoKeys (unregistered CRS, written by GDAL) | ✅ |
| EPSG codes from the projection table shipped by JOSM | ✅ (phase 2.3) |

## Supported coordinate systems

| EPSG | System |
|---|---|
| 4326 | geographic WGS84 (built in) |
| 3857 | Web Mercator (built in) |

Every other EPSG code is resolved from the projection definitions shipped
by the host application itself (JOSM's `data/projection/custom-epsg`,
read at runtime — the plugin embeds and downloads no EPSG database of
its own). Several thousands of codes are therefore available when their
projection model is one the plugin computes, for instance UTM
(32601-32760), RGF97 / Lambert-93 (2154) and its conformity zones
(3942-3950), ETRS89-LAEA (3035):

| Model (PROJ `+proj=`) | Implementation |
|---|---|
| `longlat` | geographic (ellipsoid + prime meridian) |
| `tmerc` | in-house Transverse Mercator |
| `utm` | in-house Transverse Mercator (zone form) |
| `lcc` | in-house Lambert conformal conic 1SP/2SP |

Table entries using another model — Mercator `merc`, Albers `aea`,
Lambert azimuthal equal area `laea`, stereographic `stere`, oblique
stereographic `sterea`, sloping Mercator `somerc`, equidistant conic,
polyconic, orthographic… — or a token the plugin cannot honour (unusual
axis order, named prime meridian, vertical unit) are **rejected with an
explicit reason** — never silently reprojected.

Besides the codes resolved through the table, the plugin understands
"custom" GeoKeys (value 32767, what GDAL writes for any system described
by its parameters) when they match a known model:

| Method (GeoKey 3075) | Supported system |
|---|---|
| 1 | Transverse Mercator (free ellipsoid and parameters) |
| 8 | Lambert conformal conic 2SP |
| 9, or 14 with standard parallels 3078/3079 | Lambert conformal conic 1SP |
| — | Geodetic (geographic), ellipsoid + prime meridian |

An EPSG code not listed above but **accompanied by its GeoKeys parameters**
(case of PIX4Dmatic exports, which pair an unlisted code with 3074=32767
and the full parameter set) is recognized by the same mechanism: the
plugin falls back on the declared parameters.

Any other method (Lambert azimuthal equal area 10, Albers 11,
equidistant conic 13, stereographic 14/15, Mercator with standard
parallel 7, orthographic 21, polyconic 22, cylindrical equal area 28,
oblique/sloping Mercator 9815), a sphere (1/f = 0), non-degree angular
unit or missing parameter is **rejected with an explicit error
message** — never silently reprojected. The proj4j fallback added in
phase 2.2 was **removed in phase 2.4** to keep the project under pure
GPL v2+ (no third-party code inside the jar); the models it served are
refused again.

Projections are computed in-house (Snyder series, sub-millimetric forward
accuracy, validated against GDAL/PROJ anchors in the unit tests).
Non-WGS84 datums (NTF, SAD69…) are treated as WGS84: possible offset of
the order of a hundred meters, acceptable for a map background.

## Project structure

```
src/main/java/org/openstreetmap/josm/plugins/cog/
├── CogPlugin, AddCogLayerAction    plugin entry point ("add a COG layer" action)
├── reader/                         COG reading, JOSM-free
│   ├── ByteSource, HttpRangeReader HTTP byte-range fetching (learned size/ETag, retries)
│   ├── TiffParser, CogInfo         TIFF/BigTIFF + GeoKeys parser (pyramid, georeferencing)
│   ├── RangePlanner                small ranges merged into per-block requests
│   ├── MetadataCache, MetadataCachingSource
│   │                               LRU cache of metadata only (size/ETag validated)
│   ├── TileDecoder                 NONE / Deflate / PackBits / LZW / JPEG decoding
│   ├── CogReader, CogComposer, MercatorTiles
│   │                               decoded source-tile LRU + rebuild of the
│   │                               256 px OSM display tiles
│   └── CogDumpCli                  standalone "spike" CLI (prints a COG pyramid)
├── crs/                            projection math, JOSM-free
│   ├── Crs, Projection, Ellipsoid, Geographic,
│   │   WebMercator, TransverseMercator,
│   │   LambertConformalConic2SP    in-house formulas (longlat, mercator, tmerc, lcc)
│   └── JosmEpsgTable               reads the projection table shipped by JOSM
│                                   (the plugin embeds no EPSG database of its own)
├── tools/                          CogFileServer: local static HTTP server with
│                                   byte-ranges (testing helper)
└── layer/                          JOSM integration
    ├── CogImageryLayer             background layer (JOSM cached tile layer)
    └── CogTileSource, CogTileLoader, CogTileLoaderJob
                                    on-the-fly tile synthesis + "cog" JOSM tile cache

src/test/java/…           JUnit tests (TIFF fixtures synthesized in memory)
test_data/                reference COGs (end-to-end validation and regressions)
dist/cog4josm.jar         built plugin binary
lib/                    josm-tested.jar (compile/test only, never embedded)
```

Only the top-level classes and `layer/` use the JOSM API; `reader/`, `crs/` and
`tools/` depend on the JDK alone — the reader also runs outside JOSM (`spike`
CLI) and can be reused as is.

## Building

Prerequisite: a **full JDK 17+** (containing `javac`), not a plain JRE.
Check with `javac -version`; if the command is missing, install the JDK
(Debian/Ubuntu: `sudo apt install openjdk-21-jdk-headless`).

Without `javac`, Gradle stops with an error like this (the path shown is
the JRE it detected):

    Toolchain installation '/usr/lib/jvm/java-21-openjdk-amd64' does not
    provide the required capabilities: [JAVA_COMPILER]

If several Java environments coexist, point at the right one:

```sh
export JAVA_HOME=/path/to/jdk      # must contain bin/javac
```

The compiled JOSM API is downloaded by:

```sh
./fetch-josm.sh          # places lib/josm-tested.jar
./gradlew build          # produces build/libs/cog4josm.jar + runs the tests
```

## Using

1. Copy `build/libs/cog4josm.jar` to `~/.josm/plugins/` (or "Preferences →
   Plugins → Install from file").
2. Restart JOSM.
3. Menu **Imagery → Add remote COG (GeoTIFF) background layer…** and paste
   a COG URL, for example:

```
https://sentinel-cogs.s3.us-west-2.amazonaws.com/sentinel-s2-l2a-cogs/31/U/DQ/2020/6/S2A_31UDQ_20200629_1_L2A/TCI.tif
```

## Command line diagnostics (no JOSM)

```sh
./gradlew spike --args="https://example.com/image.tif"
./gradlew spike --args="https://example.com/image.tif --tile 0 5 5 out.png"
```

Prints the pyramid (levels, tile sizes, compressions), the extent, the
GeoTIFF SRID, and can decode one tile to PNG.

## License

GPL v2 or later (like JOSM). The plugin jar contains only the plugin's own
code - no embedded third-party library - so the project ships under pure
GPL; the former proj4j dependency (Apache-2.0) was removed in phase 2.4.
