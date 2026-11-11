# cog4josm 0.4.0

JOSM plugin displaying remote **Cloud Optimized GeoTIFF (COG)** imagery as
background layers, read **through HTTP byte-ranges only**: the whole file is
never downloaded, only the needed TIFF tiles are fetched.

## New in 0.4.0 (since 0.3.0)

- **Metadata persistence across restarts** (phase 2.8): the TIFF header,
  IFDs and tile-offset tables of each COG URL now persist in one small
  file in the plugin cache directory (a few tens of KiB per source, size
  and age capped, atomically written, never raster bytes).
- **Conditional GET revalidation**: each layer open revalidates the
  persisted metadata with one conditional request (`If-None-Match` on
  the 32 KiB head read, strong ETags only). If the file did not change
  the answer is **304 with zero body bytes** — measured: reopening a
  COG goes from 1 request / 32 KiB to 1 request / 0 bytes.
- A changed file (size or ETag) drops the stale blocks, in memory and on
  disk, before re-reading: stale metadata is never served.
- The test server (`CogFileServer`) now sends strong ETags and answers
  If-None-Match, so the end-to-end tests exercise real-world server
  behaviour.
- **Speculative tail read** (phase 2.8 follow-up): header-tail COGs (the
  GDAL driver's default layout — IFDs at the very end of the file) no
  longer pay one request per IFD: a second speculative 32 KiB request
  covers the tail, and parsing costs two requests in every layout.
- **Lazy per-level tile tables** (phase 2.8 follow-up): a level's
  TileOffsets/TileByteCounts tables over 4 KiB are fetched on that
  level's first browse instead of at open — a deep pyramid explored at
  overview zooms never downloads the full-resolution tables (which can
  weigh hundreds of KiB).
- Guarantees unchanged from 0.3.0: byte-range reads only, one request
  to open a well-laid-out COG (two for header-tail files), speculative
  tile ring + base overview prefetch while browsing (0.3.0), requested
  range echoed in the User-Agent comment (debug-friendly server access
  logs), unsupported CRSs refused with an explicit reason (never
  silently reprojected), and the jar contains no third-party runtime
  code (pure GPL v2+).

## Binary

`dist/cog4josm.jar` is committed in the repository (and attached to this
release): `Plugin-Version: 0.4.0`, `Plugin-Mainversion: 19678`, Java 17+.

## Install

Drop `cog4josm.jar` into `~/.josm/plugins/` (or Preferences → Plugins →
Install a plugin from file), restart JOSM, then use the plugin menu to
open a COG URL.

---

# cog4josm 0.4.0 (notes en français)

Plugin JOSM affichant des GeoTIFF optimisés pour le cloud (COG) distants
comme couches d'arrière-plan, lus **uniquement par HTTP byte-ranges** :
jamais le fichier entier n'est téléchargé, seules les tuiles nécessaires
sont lues.

## Nouveautés de la 0.4.0 (depuis la 0.3.0)

- **Persistance des métadonnées entre redémarrages** (phase 2.8) :
  l'en-tête TIFF, les IFD et les tables de décalages de tuiles de chaque
  URL de COG persistent désormais dans un petit fichier du répertoire de
  cache du plugin (quelques dizaines de KiB par source, taille et âge
  plafonnés, écriture atomique, jamais d'octets raster).
- **Revalidation par GET conditionnel** : chaque ouverture de calque
  revalide les métadonnées persistées par une seule requête
  conditionnelle (`If-None-Match` sur la lecture tête de 32 KiB, ETag
  forts uniquement). Si le fichier n'a pas changé, la réponse est **304
  et zéro octet de corps** — mesuré : la réouverture d'un COG passe de
  1 requête / 32 KiB à 1 requête / 0 octet.
- Un fichier modifié (taille ou ETag) invalide les blocs périmés, en
  mémoire et sur disque, avant relecture : les métadonnées périmées ne
  resservent jamais.
- Le serveur de test (`CogFileServer`) envoie désormais des ETag forts
  et répond à If-None-Match, pour que les tests de bout en bout
  exercent le comportement des serveurs réels.
- **Lecture spéculative de la fin du fichier** (suivi phase 2.8) : les
  COGs header-tail (agencement GDAL par défaut — IFD en toute fin de
  fichier) ne paient plus une requête par IFD : une seconde requête
  spéculative de 32 KiB couvre la queue, et le parsing coûte deux
  requêtes dans tous les agencements.
- **Tables de tuiles paresseuses par niveau** (suivi phase 2.8) : les
  tables TileOffsets/TileByteCounts d'un niveau dépassant 4 Kio sont
  lues à la première navigation sur ce niveau plutôt qu'à l'ouverture —
  une pyramide profonde explorée en zoom dégrossi ne télécharge jamais
  les tables pleine résolution (plusieurs centaines de Kio).
- Garanties inchangées depuis la 0.3.0 : lecture par byte-ranges
  uniquement, une seule requête pour ouvrir un COG bien agencé (deux
  pour les fichiers header-tail), ourlet spéculatif de tuiles et
  préchargement de l'aperçu racine à la navigation (0.3.0), range
  demandé répété en commentaire du User-Agent (logs serveur
  exploitables), systèmes de coordonnées non pris en charge refusés
  avec un motif explicite (jamais de reprojection silencieuse), et le
  jar n'embarque aucun code tiers à l'exécution (GPL v2+ pur).

## Binaire

`dist/cog4josm.jar` est versionné dans le dépôt (et joint à cette
release) : `Plugin-Version: 0.4.0`, `Plugin-Mainversion: 19678`, Java 17+.

## Installation

Déposez `cog4josm.jar` dans `~/.josm/plugins/` (ou Préférences →
Plugins → Installer un plugin depuis un fichier), redémarrez JOSM, puis
ouvrez une URL de COG depuis le menu du plugin.
