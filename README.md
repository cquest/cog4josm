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
* Requêtes groupées : les petites plages (balises d'un IFD, tuiles voisines
  d'une fenêtre) sont fusionnées en quelques requêtes `Range:` par bloc, sur
  le modèle de l'I/O recouvrant de GDAL — environ 2 à 4 requêtes par tuile
  d'affichage au lieu d'une par tuile source.
* Cache LRU des **seules métadonnées** (en-tête, IFD, tables de décalages de
  tuiles), validé par taille/ETag : une réouverture du même COG ne coûte
  qu'une requête de sonde. Le contenu raster n'est jamais mis en cache en
  octets bruts (seulement en images décodées).
* Pas de dépendance externe : uniquement le JDK et l'API JOSM.

## État (v0.1)

| Fonctionnalité | État |
|---|---|
| Lecture byte-ranges + sonde `Range:` | ✅ |
| Groupement des petites plages en requêtes par bloc | ✅ |
| Cache LRU metadata (jamais le raster brut) | ✅ |
| Parseur TIFF/GeoTIFF (IFD, sous-IFD, GeoKeys) | ✅ |
| Pyramide multi-niveaux, choix du meilleur niveau | ✅ |
| Recomposition de tuiles web-mercatator | ✅ (EPSG:3857/4326) |
| Couche JOSM fonctionnelle (menu Imagery) | ✅ |
| Reprojection (UTM, Lambert-93) | ✅ |
| GeoKeys « custom » (CRS non enregistré, écrits par GDAL) | ✅ |
| Panneau STAC (Panoramax) | ⏳ phase 3 |

## Systèmes de coordonnées pris en charge

| EPSG | Système |
|---|---|
| 4326 | géographique WGS84 |
| 3857 | Web Mercator |
| 32601–32660 | UTM WGS84 nord (zone N) |
| 32701–32760 | UTM WGS84 sud (zone N) |
| 2154 | RGF97 / Lambert-93 (France métropolitaine) |

En plus des SRID EPSG enregistrés, le plugin comprend les GeoKeys
« custom » (valeur 32767, ce que GDAL écrit pour tout système décrit par
ses paramètres) lorsque ceux-ci correspondent à un modèle connu :

| Méthode (GeoKey 3075) | Système pris en charge |
|---|---|
| 1 | Transverse de Mercator (ellipsoïde et paramètres libres) |
| 8 | Conique conforme de Lambert 1SP/2SP |
| — | Systématique (géographique), ellipsoïde + premier méridien |

Un code EPSG non listé ci-dessus mais **accompagné de ses paramètres
GeoKeys** (cas des exports PIX4Dmatic vers Lambert-43/EPSG:3943, par
exemple) est reconnu par le même mécanisme : le plugin retombe sur les
paramètres déclarés.

Tout autre méthode (Albers 11, Mercator oblique…), unité angulaire non
degré ou paramètre manquant est **refusé avec un message d'erreur
explicite** — jamais reprojeté silencieusement.

Les projections sont calculées en interne (séries de Snyder, précision
sub-millimétrique en avant, validée contre GDAL/PROJ). Les datums non-WGS84
(NTF, SAD69…) sont traités comme WGS84 : décalage possible de l'ordre de la
centaine de mètres, acceptable pour un fond cartographique.

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
