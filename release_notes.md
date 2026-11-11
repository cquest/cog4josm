# cog4josm 0.3.0

JOSM plugin displaying remote **Cloud Optimized GeoTIFF (COG)** imagery as
background layers, read **through HTTP byte-ranges only**: the whole file is
never downloaded, only the needed TIFF tiles are fetched.

## New in 0.3.0 (since 0.2.0)

- **Speculative tile ring** (phase 2.7 E1): every source-tile fetch window
  is padded by one extra row/column of tiles, fetched in the same merged
  byte-range blocks and decoded into the tile LRU (not part of the result).
  Panning to the neighbouring display tiles usually costs no request at
  all; the ring is only extended when the requested window actually misses,
  and is capped (48 tiles / 8 MiB) so huge reprojection windows never pad.
- **Base overview prefetch** (phase 2.7 E2): at layer load, the pyramid's
  base overview is pre-fetched in the background in one merged request when
  it is under 2 MiB — the zoomed-out view and zoom-out fallbacks are free.
- Measured on a real COG over local HTTP (90 display tiles swept at z14):
  20 → 12 range requests while the transferred bytes stayed flat
  (+0.007%: on a well-laid-out COG the ring is mostly absorbed by the
  neighbouring windows).
- Guarantees unchanged from 0.2.0: opening a COG costs a single 32 KiB
  head read (no probe request), the requested range is echoed in the
  User-Agent comment (debug-friendly server access logs), unsupported CRSs
  are refused with an explicit reason (never silently reprojected), and the
  jar contains no third-party runtime code (pure GPL v2+).

## Binary

`dist/cog4josm.jar` is committed in the repository (and attached to this
release): `Plugin-Version: 0.3.0`, `Plugin-Mainversion: 19678`, Java 17+.

## Install

Drop `cog4josm.jar` into `~/.josm/plugins/` (or Preferences → Plugins →
Install a plugin from file), restart JOSM, then use the plugin menu to
open a COG URL.

---

# cog4josm 0.3.0 (notes en français)

Plugin JOSM affichant des GeoTIFF optimisés pour le cloud (COG) distants
comme couches d'arrière-plan, lus **uniquement par HTTP byte-ranges** :
jamais le fichier entier n'est téléchargé, seules les tuiles nécessaires
sont lues.

## Nouveautés de la 0.3.0 (depuis la 0.2.0)

- **Ourlet spéculatif de tuiles** (phase 2.7 E1) : chaque fenêtre de
  lecture de tuiles source est ourlée d'une rangée et colonne
  supplémentaires, lues dans les mêmes blocs fusionnés et décodées dans le
  LRU de tuiles (sans figurer dans le résultat). Un déplacement vers les
  tuiles d'affichage voisines ne coûte en général aucune requête ;
  l'ourlet n'est tendu que si la fenêtre demandée contient un raté, et est
  plafonné (48 tuiles / 8 MiB) pour que les immenses fenêtres de reprojection
  ne se gonflent jamais.
- **Préchargement de l'aperçu racine** (phase 2.7 E2) : au chargement du
  calque, l'aperçu racine de la pyramide est pré-chargé en arrière-plan en
  une seule requête fusionnée s'il fait moins de 2 MiB — la vue dézoomée
  initiale et les replis de dézoomage deviennent gratuits.
- Mesuré sur un COG réel servi en HTTP local (balayage de 90 tuiles
  d'affichage au zoom 14) : 20 → 12 requêtes de plages, pour un volume
  d'octets stable (+0,007 % : sur un COG bien agencé l'ourlet est absorbé
  par les fenêtres voisines).
- Garanties inchangées depuis la 0.2.0 : une seule requête (lecture tête
  de 32 KiB, sans sonde) pour ouvrir un COG, le range demandé est répété
  en commentaire du User-Agent (logs serveur exploitables), les systèmes
  de coordonnées non pris en charge sont refusés avec un motif explicite
  (jamais de reprojection silencieuse), et le jar n'embarque aucun code
  tiers à l'exécution (GPL v2+ pur).

## Binaire

`dist/cog4josm.jar` est versionné dans le dépôt (et joint à cette
release) : `Plugin-Version: 0.3.0`, `Plugin-Mainversion: 19678`, Java 17+.

## Installation

Déposez `cog4josm.jar` dans `~/.josm/plugins/` (ou Préférences →
Plugins → Installer un plugin depuis un fichier), redémarrez JOSM, puis
ouvrez une URL de COG depuis le menu du plugin.
