# josm-cog

Plugin JOSM qui affiche des images **COG (Cloud Optimized GeoTIFF)** distantes
comme couches d'arrière-plan, en les lisant **par HTTP byte-ranges** : jamais
le fichier entier n'est téléchargé, seules les tuiles TIFF utiles sont
récupérées (et mises en cache mémoire).

## Principe

```
HTTP Range ──► TiffParser ──► CogInfo (pyramide + géoréférencement)
                                  │
JOSM tuile 256px ──► CogComposer ─┤  choisit le niveau de pyramide adéquat
                     (recomposition)◄── CogReader (tuiles TIFF décodées)
```

* Le plugin se branche sur la chaîne d'imagerie standard de JOSM
  (`AbstractCachedTileSourceLayer` + `TileLoader` dédié) : cache, zoom,
  transparence, filtres d'imagerie fonctionnent tel quel.
* Les tuiles d'affichage (grille OSM 256 px) sont **recomposées** à la volée
  à partir des tuiles natives du COG (256/512/1024 px), quel que soit le
  décalage de l'origine géoréférencée.
* Décodeurs TIFF intégrés : pas (1), Deflate (8/32946), PackBits (32773),
  LZW (5), JPEG (6, via ImageIO). Gris 8/16 bits, RGB(A), predictor horizontal.
* Pas de dépendance externe : uniquement le JDK et l'API JOSM.

## État (v0.1)

| Fonctionnalité | État |
|---|---|
| Lecture byte-ranges + sonde `Range:` | ✅ |
| Parseur TIFF/GeoTIFF (IFD, sous-IFD, GeoKeys) | ✅ |
| Pyramide multi-niveaux, choix du meilleur niveau | ✅ |
| Recomposition de tuiles web-mercatator | ✅ (EPSG:3857/4326) |
| Couche JOSM fonctionnelle (menu Imagery) | ✅ (à valider en GUI) |
| Reprojection (Lambert-93, etc.) | ⏳ phase 2 |
| Panneau STAC (Panoramax) | ⏳ phase 3 |

## Construire

Prérequis : JDK 17+. L'API JOSM compilée est téléchargée par :

```sh
./fetch-josm.sh          # place lib/josm-tested.jar
./gradlew build          # produit build/libs/cog.jar + lance les tests
```

## Utiliser

1. Copier `build/libs/cog.jar` dans `~/.josm/plugins/` (ou
   « Préférences → Parameters → Plugins → Installer à partir d'un fichier local »).
2. Redémarrer JOSM.
3. Menu **Imagerie → Add remote COG (GeoTIFF) background layer…** et coller
   l'URL d'un COG, par exemple :

```
https://sentinel-cogs.s3.us-west-2.amazonaws.com/sentinel-s2-l2a-cogs/31/U/DQ/2020/6/S2A_31UDQ_20200629_1_L2A/TCI.tif
```

## Diagnostic en ligne de commande (sans JOSM)

```sh
./gradlew spike --args="https://exemple.com/image.tif"
./gradlew spike --args="https://exemple.com/image.tif --tile 0 5 5 sortie.png"
```

Affiche la pyramide (niveaux, tailles de tuiles, compressions),
l'emprise, le SRID GeoTIFF, et sait décoder une tuile en PNG.

## Licence

GPL v2 ou ultérieure (comme JOSM).
