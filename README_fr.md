# cog4josm

(English version: [README.md](README.md))

> Note du vite-codeur :
> Ce projet a été "vite-codé" pour expérimenter les possibilités d'utilisation en local de Qwen3.8-flash-next couplé à opencode.
> Il est mis sous licence GPL conformément à sa dépendance à JOSM, mais aurait plus naturellement été mis sous WTFPL.
> Je ne compte pas le maintenir, mais comme il fonctionne et peut être utile, je préfère le partager.


Plugin JOSM qui affiche des images **COG ([Cloud Optimized GeoTIFF](https://cogeo.org/))** distantes
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
* Décodeurs TIFF intégrés : aucune (1), Deflate (8/32946), PackBits (32773),
  LZW (5), JPEG (6, via ImageIO). Gris 8/16 bits, RGB(A), predictor horizontal.
* Requêtes groupées : les petites plages (balises d'un IFD, tuiles voisines
  d'une fenêtre) sont fusionnées en quelques requêtes `Range:` par bloc, sur
  le modèle de l'I/O recouvrant de GDAL — environ 2 à 4 requêtes par tuile
  d'affichage au lieu d'une par tuile source.
* Remplissage spéculatif : chaque fenêtre de lecture est ourlée d'une
  rangée ou colonne supplémentaire de tuiles source (décodées dans le LRU
  de tuiles,
  sans figurer dans le résultat), et l'aperçu racine est pré-chargé en
  arrière-plan au chargement du calque s'il fait moins de 2 MiB — un
  déplacement vers les tuiles d'affichage voisines ne coûte en général
  aucune requête (mesuré : 20 → 12 requêtes sur un balayage de 90 tuiles,
  octets stables).
* Lecture spéculative d'en-tête : l'ouverture d'une source HTTP n'émet
  qu'une seule requête `bytes=0-32767`, qui prouve le support de `Range:`
  (plus de requête de sonde), révèle taille et ETag, et apporte en un aller
  simple l'en-tête, les IFD et les GeoKeys d'un COG bien agencé. Pour un
  COG header-tail (agencement GDAL par défaut : IFD en fin de fichier),
  une seconde requête spéculative couvre les 32 KiB finaux — deux requêtes
  par ouverture dans tous les cas, jamais une par IFD.
* Tables de tuiles paresseuses par niveau : la table de décalages et de
  tailles de tuiles d'un niveau dépassant 4 Kio n'est pas lue à
  l'ouverture mais à la première navigation sur ce niveau — explorer une
  pyramide profonde en zoom dégrossi ne télécharge jamais les tables
  pleine résolution (sur un fixture à 5 niveaux : 178 Kio de tables
  restent côté serveur tant que leurs niveaux ne sont pas utilisés).
* Cache LRU des **seules métadonnées** (en-tête, IFD, tables de décalages de
  tuiles), validé par taille/ETag et consulté par containment (un bloc plus
  grand sert les lectures plus petites) : une réouverture du même COG ne
  coûte que cette requête tête. Le contenu raster n'est jamais mis en cache
  en octets bruts (seulement en images décodées).
* Les métadonnées survivent aussi aux redémarrages de JOSM : les blocs
  persistent dans un petit fichier par URL sous le répertoire de cache du
  plugin, et chaque ouverture se revalide par une requête conditionnelle
  `If-None-Match` — si le fichier n'a pas changé, la réponse est 304 et les
  métadonnées ne coûtent **aucun octet** transmis (mesuré : la réouverture
  passe de 32 KiB à 0 octet pour une requête). Une taille/ETag modifiée
  invalide mémoire et disque avant relecture.
* Les tuiles d'affichage 256 px composées sont stockées dans **le cache
  d'imagerie intégré de JOSM** (région JCS « cog » : gestion mémoire JOSM
  et cache tuiles sur disque, vidé par les actions JOSM habituelles).
  Les clés combinent une empreinte de la source (URL + taille + ETag), la
  politique de transparence et z/x/y : un fichier modifié ne ressert
  jamais de tuiles périmées ; les
  tuiles d'erreur et les tuiles vides ne sont jamais mises en cache.
* Transparence noir → alpha : les sources RGB (ou JPEG YCbCr) sans canal
  alpha voient leurs pixels quasi noirs rendus transparents — canaux ≤ 8
  par défaut (absorbe les franges JPEG), ou seulement si le fichier
  déclare `GDAL_NODATA` noir. Activé par défaut ; réglable par couche
  dans son menu (« Transparence des noirs ») ou globalement par la
  préférence `cog.transparency` (`black_near` / `metadata` / `none`).
  Les sources en niveaux de gris ou portant déjà un alpha ne sont jamais
  touchées.
* Carte propre hors couverture : les tuiles au-delà de l'emprise du COG ne
  sont même pas créées (la grille de tuiles est bornée par l'emprise), et
  les tuiles de bord qui ne composent rien reçoivent une image
  transparente au lieu du motif sablier « pas d'image » de JOSM.
* Dépendances minimales : **JDK + API JOSM uniquement**. Le jar du plugin
  ne contient aucun code tiers (GPL v2+ pure). Les paramètres des CRS
  viennent toujours du fichier lui-même (GeoKeys) ou de la table de
  projections embarquée par JOSM — le plugin n'embarque ni ne télécharge
  aucune base EPSG propre ; les modèles qu'il ne sait pas calculer sont
  refusés.

## État (v0.5)

| Fonctionnalité | État |
|---|---|
| Lecture byte-ranges, blocs tête + queue à l'ouverture | ✅ |
| Tables de tuiles lues paresseusement, par niveau | ✅ |
| Transparence noir → alpha (sources RGB sans alpha) | ✅ (phase 3) |
| Pas de sablier hors de la couverture du COG | ✅ |
| Groupement des petites plages en requêtes par bloc | ✅ |
| Ourlet spéculatif de tuiles + préchargement de l'aperçu racine | ✅ |
| Métadonnées persistées + GET conditionnel (304 à la réouverture) | ✅ |
| Cache LRU metadata (jamais le raster brut) | ✅ |
| Tuiles composées dans le cache d'imagerie JOSM (mémoire + disque) | ✅ |
| Parseur TIFF/GeoTIFF (IFD, sous-IFD, GeoKeys) | ✅ |
| Conteneur BigTIFF (décalages 64 bits, fichiers PIX4Dmatic) | ✅ |
| IFD de masque alpha entrelacés (photometrique 4) | détectés, ignorés (composition plus tard) |
| Pyramide multi-niveaux, choix du meilleur niveau | ✅ |
| Recomposition de tuiles web-mercator | ✅ (EPSG:3857/4326) |
| Couche JOSM fonctionnelle (menu Imagery) | ✅ |
| Reprojection (UTM, Lambert-93) | ✅ |
| GeoKeys « custom » (CRS non enregistré, écrits par GDAL) | ✅ |
| Codes EPSG pris de la table de projections embarquée par JOSM | ✅ (phase 2.3) |

## Systèmes de coordonnées pris en charge

| EPSG | Système |
|---|---|
| 4326 | géographique WGS84 (intégré) |
| 3857 | Web Mercator (intégré) |

Tout autre code EPSG est résolu à partir des définitions de projection
embarquées par l'application hôte elle-même (le fichier
`data/projection/custom-epsg` de JOSM, lu à l'exécution — le plugin
n'embarque ni ne télécharge aucune base EPSG propre). Plusieurs milliers
de codes sont donc disponibles lorsque leur modèle de projection est un
modèle que le plugin sait calculer, par exemple UTM (32601-32760),
RGF97 / Lambert-93 (2154) et ses zones de conformité (3942-3950),
ETRS89-LAEA (3035) :

| Modèle (PROJ `+proj=`) | Implémentation |
|---|---|
| `longlat` | géographique (ellipsoïde + premier méridien) |
| `tmerc` | Transverse de Mercator en interne |
| `utm` | Transverse de Mercator en interne (forme zone) |
| `lcc` | Conique conforme de Lambert 1SP/2SP en interne |

Les entrées de la table utilisant un autre modèle — Mercator `merc`,
conique équivalente d'Albers `aea`, conique équivalente de Lambert
`laea`, stéréographique `stere`, stéréographique oblique `sterea`,
Mercator oblique `somerc`, conique équidistante, polyconique,
orthographique… — ou un jeton que le plugin ne peut pas honorer (ordre
d'axes inhabituel, premier méridien nommé, unité verticale) sont
**refusées avec une raison explicite** — jamais reprojetées
silencieusement.

En plus des codes résolus via la table, le plugin comprend les GeoKeys
« custom » (valeur 32767, ce que GDAL écrit pour tout système décrit par
ses paramètres) lorsque ceux-ci correspondent à un modèle connu :

| Méthode (GeoKey 3075) | Système pris en charge |
|---|---|
| 1 | Transverse de Mercator (ellipsoïde et paramètres libres) |
| 8 | Conique conforme de Lambert 2SP |
| 9, ou 14 avec parallèles étalons 3078/3079 | Conique conforme de Lambert 1SP |
| — | Géodésique (géographique), ellipsoïde + premier méridien |

Un code EPSG non listé ci-dessus mais **accompagné de ses paramètres
GeoKeys** (cas des exports PIX4Dmatic, qui associent un code non listé à
3074=32767 et au jeu complet de paramètres) est reconnu par le même
mécanisme : le plugin retombe sur les paramètres déclarés.

Toute autre méthode (conique équivalente de Lambert 10, conique
équivalente d'Albers 11, conique équidistante 13, stéréographique
14/15, Mercator avec parallèle étalon 7, orthographique 21, polyconique
22, cylindrique équivalente 28, Mercator oblique 9815), une sphère
(1/f = 0), unité angulaire non degré ou paramètre manquant est
**refusée avec un message d'erreur explicite** — jamais reprojetée
silencieusement. Le repli proj4j ajouté en phase 2.2 a été **supprimé en
phase 2.4** pour garder le projet sous GPL v2+ pure (aucun code tiers
dans le jar) ; les modèles qu'il servait sont de nouveau refusés.

Les projections sont calculées en interne (séries de Snyder, précision
sub-millimétrique en avant, validée contre des ancres GDAL/PROJ dans les
tests unitaires). Les datums non-WGS84 (NTF, SAD69…) sont traités comme
WGS84 : décalage possible de l'ordre de la centaine de mètres,
acceptable pour un fond cartographique.

## Structure du projet

```
src/main/java/org/openstreetmap/josm/plugins/cog/
├── CogPlugin, AddCogLayerAction    entrée du plugin (action « ajouter un calque COG »)
├── reader/                         lecture des COG, sans dépendance à JOSM
│   ├── ByteSource, HttpRangeReader lecture par HTTP byte-ranges (taille/ETag apprises, retries)
│   ├── TiffParser, CogInfo         parseur TIFF/BigTIFF + GeoKeys (pyramide, géoréférence)
│   ├── RangePlanner                fusion des petites plages en requêtes par bloc
│   ├── MetadataCache, MetadataCachingSource
│   │                               cache LRU des seules métadonnées (validé taille/ETag)
│   ├── TileDecoder                 décodage NONE / Deflate / PackBits / LZW / JPEG
│   ├── CogReader, CogComposer, MercatorTiles
│   │                               LRU de tuiles source décodées + recomposition
│   │                               des tuiles d'affichage OSM 256 px
│   └── CogDumpCli                  CLI autonome « spike » (affiche la pyramide d'un COG)
├── crs/                            calculs de projection, sans dépendance à JOSM
│   ├── Crs, Projection, Ellipsoid, Geographic,
│   │   WebMercator, TransverseMercator,
│   │   LambertConformalConic2SP    formules internes (longlat, mercator, tmerc, lcc)
│   └── JosmEpsgTable               lit la table de projections embarquée par JOSM
│                                   (le plugin n'embarque aucune base EPSG propre)
├── tools/                          CogFileServer : serveur HTTP statique local
│                                   avec byte-ranges (outil de test)
└── layer/                          intégration JOSM
    ├── CogImageryLayer             couche d'arrière-plan (couche tuiles JOSM avec cache)
    └── CogTileSource, CogTileLoader, CogTileLoaderJob
                                    synthèse des tuiles à la volée, cache JOSM « cog »

src/test/java/…          tests JUnit (fixtures TIFF synthétisées en mémoire)
test_data/               COG de référence (validation E2E et non-régression)
dist/cog4josm.jar        binaire du plugin construit
lib/                   josm-tested.jar (compile/test uniquement, jamais embarqué)
```

Seules les classes de premier niveau et `layer/` utilisent l'API JOSM ; `reader/`,
`crs/` et `tools/` ne dépendent que du JDK — le lecteur fonctionne donc aussi hors
JOSM (CLI `spike`) et peut être réutilisé tel quel.

## Construire

Prérequis : un **JDK 17+ complet** (contenant `javac`), pas un simple
JRE. Vérifiez avec `javac -version` ; si la commande manque, installez
le JDK (Debian/Ubuntu : `sudo apt install openjdk-21-jdk-headless`).

Sans `javac`, Gradle s'arrête sur une erreur de ce genre (le chemin
indiqué est le JRE qu'il a détecté) :

    Toolchain installation '/usr/lib/jvm/java-21-openjdk-amd64' does not
    provide the required capabilities: [JAVA_COMPILER]

Si plusieurs environnement Java coexistent, désignez le bon :

```sh
export JAVA_HOME=/chemin/vers/jdk      # doit contenir bin/javac
```

L'API JOSM compilée est téléchargée par :

```sh
./fetch-josm.sh          # place lib/josm-tested.jar
./gradlew build          # produit build/libs/cog4josm.jar + lance les tests
```

## Utiliser

1. Copier `build/libs/cog4josm.jar` dans `~/.josm/plugins/` (ou
   « Préférences → Paramètres → Plugins → Installer à partir d'un fichier local »).
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

GPL v2 ou ultérieure (comme JOSM). Le jar du plugin ne contient que le code
du plugin lui-même — aucune bibliothèque tierce embarquée — le projet est
donc distribué sous GPL pure ; l'ancienne dépendance proj4j (Apache-2.0) a
été supprimée en phase 2.4.
