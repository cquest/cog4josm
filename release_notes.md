# cog4josm 0.5.0

JOSM plugin displaying remote **Cloud Optimized GeoTIFF (COG)** imagery as
background layers, read **through HTTP byte-ranges only**: the whole file is
never downloaded, only the needed TIFF tiles are fetched.

## New in 0.5.0 (phase 3)

Add transparency on near-black pixels in JPEG which do not support ahpla channel.

- **Black-to-alpha transparency** for sources whose pixels are RGB (or
  JPEG YCbCr) **without an alpha channel**: near-black pixels are rendered
  fully transparent instead of as a black mask. Grey sources and sources
  that already carry alpha are never touched.
- **On by default**: any pixel whose three channels are all ≤ 8 becomes
  transparent — the slack absorbs the JPEG fringes around black areas.
  A legitimately black background therefore disappears too: the layer's
  menu has a "Transparency of black pixels" entry cycling through
  on → metadata only → off, and the `cog.transparency` preference
  (`black_near` / `metadata` / `none`) sets the default for new layers.
- **Metadata-only mode**: transparency applies only when the file
  explicitly declares a black no-data value (GDAL `GDAL_NODATA` tag).
- Switching the policy is free of network cost: each policy keeps its own
  decoded tiles, and the policy name is part of the tile cache keys, so
  composed tiles from another policy are never served.
- **No hourglass outside the coverage**: where the COG has no imagery the
  map now stays clean instead of showing JOSM's "no image" hourglass
  pattern. Tiles beyond the COG's extent are never created (the tile
  grid is clamped to the extent at every zoom), and edge tiles that
  compose to nothing are painted with a shared transparent image.

## Guarantees unchanged from 0.4.0

Byte-range reads only (never the whole file); one request to open a
well-laid-out COG, two for header-tail files, with no probe request;
metadata persisted across restarts and revalidated with a conditional
GET (304, zero body bytes on reopen); lazy per-level tile tables (huge
TileOffsets/TileByteCounts tables fetched on a level's first browse);
speculative tile ring + base overview prefetch while browsing;
requested range echoed in the User-Agent comment (debug-friendly server
access logs); unsupported CRSs refused with an explicit reason (never
silently reprojected); the jar contains no third-party runtime code
(pure GPL v2+).

## Binary

`dist/cog4josm.jar` is committed in the repository (and attached to this
release): `Plugin-Version: 0.5.0`, `Plugin-Mainversion: 19678`, Java 17+.

## Install

Drop `cog4josm.jar` into `~/.josm/plugins/` (or Preferences → Plugins →
Install a plugin from file), restart JOSM, then use the plugin menu to
open a COG URL.

---

# cog4josm 0.5.0 (notes en français)

Plugin JOSM affichant des GeoTIFF optimisés pour le cloud (COG) distants
comme couches d'arrière-plan, lus **uniquement par HTTP byte-ranges** :
jamais le fichier entier n'est téléchargé, seules les tuiles nécessaires
sont lues.

## Nouveautés de la 0.5.0 (phase 3)

Ajout de la transparence des pixels quasi noirs dans les JPEG qui n'ont pas de canal alpha.

- **Transparence noir → alpha** pour les sources dont les pixels sont
  RGB (ou JPEG YCbCr) **sans canal alpha** : les pixels quasi noirs
  deviennent entièrement transparents au lieu de former un masque noir.
  Les sources en niveaux de gris ou portant déjà un alpha ne sont jamais
  touchées.
- **Activé par défaut** : tout pixel dont les trois canaux valent ≤ 8
  devient transparent — la marge absorbe les franges JPEG autour des
  zones noires. Un fond noir légitime disparaît donc lui aussi : le menu
  du calque offre une entrée « Transparence des noirs » qui boucle sur
  activé → métadonnée seule → désactivé, et la préférence
  `cog.transparency` (`black_near` / `metadata` / `none`) fixe le défaut
  des nouvelles couches.
- **Mode métadonnée seule** : la transparence ne s'applique que si le
  fichier déclare explicitement une valeur d'absence noire (balise GDAL
  `GDAL_NODATA`).
- Changer de politique ne coûte aucune requête : chaque politique garde
  ses propres tuiles décodées, et son nom entre dans les clés du cache
  de tuiles — les tuiles composées d'une autre politique ne resservent
  jamais.
- **Pas de sablier hors de la couverture** : là où le COG n'a pas
  d'image, la carte reste propre au lieu d'afficher le motif sablier
  « pas d'image » de JOSM. Les tuiles au-delà de l'emprise du COG ne
  sont même pas créées (la grille de tuiles est bornée par l'emprise à
  chaque zoom), et les tuiles de bord qui ne composent rien sont
  peintes avec une image transparente partagée.

## Garanties inchangées depuis la 0.4.0

Lecture par byte-ranges uniquement (jamais le fichier entier) ; une
requête pour ouvrir un COG bien agencé, deux pour les fichiers header-tail,
sans requête sonde ; métadonnées persistées entre redémarrages et
revalidées par GET conditionnel (304, zéro octet de corps à la
réouverture) ; tables de tuiles paresseuses par niveau (les grandes
tables TileOffsets/TileByteCounts sont lues à la première navigation sur
le niveau) ; ourlet spéculatif de tuiles et préchargement de l'aperçu
racine à la navigation ; range demandé répété en commentaire du
User-Agent (logs serveur exploitables) ; systèmes de coordonnées non
pris en charge refusés avec un motif explicite (jamais de reprojection
silencieuse) ; le jar n'embarque aucun code tiers à l'exécution
(GPL v2+ pur).

## Binaire

`dist/cog4josm.jar` est versionné dans le dépôt (et joint à cette
release) : `Plugin-Version: 0.5.0`, `Plugin-Mainversion: 19678`, Java 17+.

## Installation

Déposez `cog4josm.jar` dans `~/.josm/plugins/` (ou Préférences →
Plugins → Installer un plugin depuis un fichier), redémarrez JOSM, puis
ouvrez une URL de COG depuis le menu du plugin.
