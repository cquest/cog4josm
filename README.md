# josm-cog

(French version: [README_fr.md](README_fr.md))

JOSM plugin displaying remote **COG (Cloud Optimized GeoTIFF)** imagery as
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
* LRU cache of **metadata only** (header, IFDs, tile offset tables),
  validated by size/ETag: reopening the same COG costs a single probe
  request. Raster content is never cached as raw bytes (only as decoded
  images).
* No external dependency: JDK and JOSM API only.

## Status (v0.1)

| Feature | Status |
|---|---|
| Byte-range reads + `Range:` probe | ✅ |
| Small ranges merged into block requests | ✅ |
| Metadata LRU cache (never raw raster) | ✅ |
| TIFF/GeoTIFF parser (IFD, sub-IFD, GeoKeys) | ✅ |
| BigTIFF container (64-bit offsets, PIX4Dmatic files) | ✅ |
| Multi-level pyramid, best level choice | ✅ |
| Web-Mercator tile recomposition | ✅ (EPSG:3857/4326) |
| Working JOSM layer (Imagery menu) | ✅ |
| Reprojection (UTM, Lambert-93) | ✅ |
| "Custom" GeoKeys (unregistered CRS, written by GDAL) | ✅ |
| STAC panel (Panoramax) | ⏳ phase 3 |

## Supported coordinate systems

| EPSG | System |
|---|---|
| 4326 | geographic WGS84 |
| 3857 | Web Mercator |
| 32601–32660 | UTM WGS84 north (zone N) |
| 32701–32760 | UTM WGS84 south (zone N) |
| 2154 | RGF97 / Lambert-93 (metropolitan France) |

Besides the registered EPSG SRIDs, the plugin understands "custom" GeoKeys
(value 32767, what GDAL writes for any system described by its parameters)
when they match a known model:

| Method (GeoKey 3075) | Supported system |
|---|---|
| 1 | Transverse Mercator (free ellipsoid and parameters) |
| 8 | Lambert conformal conic 1SP/2SP |
| — | Geodetic (geographic), ellipsoid + prime meridian |

An EPSG code not listed above but **accompanied by its GeoKeys parameters**
(case of PIX4Dmatic exports to Lambert-43/EPSG:3943, for instance) is
recognized by the same mechanism: the plugin falls back on the declared
parameters.

Any other method (Albers 11, oblique Mercator…), non-degree angular unit
or missing parameter is **rejected with an explicit error message** —
never silently reprojected.

Projections are computed in-house (Snyder series, sub-millimetric forward
accuracy, validated against GDAL/PROJ). Non-WGS84 datums (NTF, SAD69…) are
treated as WGS84: possible offset of the order of a hundred meters,
acceptable for a map background.

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
./gradlew build          # produces build/libs/cog.jar + runs the tests
```

## Using

1. Copy `build/libs/cog.jar` to `~/.josm/plugins/` (or "Preferences →
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

GPL v2 or later (like JOSM).
