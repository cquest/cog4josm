# josm_cog — instructions pour agents

Plugin JOSM affichant des COG (Cloud Optimized GeoTIFF) distants comme couches
d'arrière-plan, par lectures HTTP byte-ranges uniquement.

## Principes non négociables

- **Jamais de téléchargement complet du fichier** : toute lecture passe par
  `ByteSource`/`HttpRangeReader` (requêtes `Range:`). Un serveur qui ne
  gère pas les ranges doit être refusé (`RangeNotSupportedException`).
- **Aucun proxy serveur** : le plugin parle directement au serveur de tuiles.
- **Aucune dépendance externe** : uniquement le JDK + l'API JOSM
  (`lib/josm-tested.jar`, fournie par `./fetch-josm.sh`). Pas de GDAL, pas de
  bibliothèque TIFF/JPEG au runtime.
- Licence **GPL v2+** (comme JOSM) ; ne pas ajouter de fichiers sous licence
  incompatible. Pas d'en-tête de licence par fichier (un `LICENSE` racine
  suffit, suivre l'existant).
- V1 n'accepte que **EPSG:3857 et EPSG:4326** ; tout autre CRS doit produire
  une erreur explicite (pas de reprojection silencieuse). La reprojection
  (Lambert-93, UTM…) est la phase 2 du plan.

## Langue et style

- **Commentaires de code et messages de commit en français sans accents**
  (contrainte d'encodage historique du code : « arriere-plan », « ecriture »).
  La documentation Markdown (README, ce fichier) peut garder les accents.
  Identificateurs et API en anglais.
- Imports en tête de fichier, sans import wildcard ; `Logging` de JOSM pour
  toute journalisation, messages préfixés « josm-cog : ».

## Construire / tester

```sh
export JAVA_HOME=/home/llm/tools/jdk-21.0.13+9   # JDK 21 local (JDK 17+ requis)
./fetch-josm.sh          # si lib/josm-tested.jar absent
./gradlew build          # jar + tests -> build/libs/cog.jar
./gradlew test           # tests seuls (JUnit 5)
```

- **Toujours vérifier la ligne « BUILD SUCCESSFUL »** dans la sortie complète.
  Ne jamais écrire `./gradlew build 2>&1 | tail -1 && cp …` : `tail -1` peut
  masquer un échec de compilation (le message « 1 actionable task ») et le
  `cp` déployer un jar périmé.
- Cible Java 17 (`options.release = 17`) : ne pas utiliser d'API plus récente.

## Tâches gradle utiles

- `./gradlew spike --args="https://exemple.tif"` : CLI de diagnostic, affiche
  pyramide/emprise/SRID sans JOSM (à exécuter en premier sur tout nouveau COG).
- `./gradlew spike --args="https://exemple.tif --tile NIVEAU TX TY out.png"` :
  décode une tuile native en PNG.
- `./gradlew serve-cog --args=". 8765"` : `CogFileServer` (outils de test),
  serveur statique JDK avec byte-ranges. Exemple local :
  `http://127.0.0.1:8765/cog-paris-3857.tif`.

## Architecture (packages sous `org.openstreetmap.josm.plugins.cog`)

- `reader/` : cœur indépendant de JOSM (testable sans GUI).
  `HttpRangeReader` (ByteSource HTTP) → `TiffParser` (IFD, GeoKeys) →
  `CogInfo` (pyramide + géoréférencement) → `CogReader` (tuiles décodées,
  cache LRU) → `CogComposer` (recomposition des tuiles OSM 256 px) et
  `TileDecoder` (NONE/Deflate/PackBits/LZW/JPEG).
- `layer/` : intégration JOSM. `CogImageryLayer` extends
  `AbstractCachedTileSourceLayer<CogTileSource>` ; `CogTileSource` est une
  source TMS sans URL (synthèse à la volée) qui **porte le `CogReader`** ;
  `CogTileLoaderJob` récupère le lecteur via `tile.getSource()`.
- `AddCogLayerAction` : dialogue URL + ouverture + zoom vers l'emprise.
- `tools/CogFileServer` : tests uniquement (ne pas lier au plugin).

## Pièges TIFF/COG (tous déjà rencontrés — ne pas régresser)

- Numérotation **libtiff** des balises tuiles : 322 TileWidth, 323 TileLength,
  324 TileOffsets, 325 TileByteCounts. La balise **JPEGTables est 347**
  (530 = YCbCrSubsampling, pas des tables JPEG !).
- **Tuiles JPEG sans tables embarquées** : GDAL (driver COG) met les tables
  DQT dans la balise 347 et non dans le flux. `TileDecoder.withJpegTables`
  les réinsère quand le flux n'a pas de DQT avant SOS. Fixture de test :
  `src/test/resources/gdal-jpeg-{tables,tile}.bin`.
- **Mercator sphérique** (grille OSM/JOSM/JMapViewer) : R = 6378137,
  `HALF_CIRCUMFERENCE = π·R = 20037508,34` (la valeur « 20037504.17 » qui
  circule est fausse). Les formules latToY/yToLat doivent utiliser R, pas
  π·R. Un aller-retour seul ne détecte pas une erreur symétrique : les tests
  contiennent des **valeurs absolues de contrôle**, les conserver.

## Pièges API JOSM (validé sur r19678, `lib/josm-tested.jar`)

- **Manifest jar** : uniquement les noms officiels `Plugin-Class`,
  `Plugin-Mainversion`, `Plugin-Minimum-Java-Version`. Les variantes du type
  `Plugin-Main-Version` / `Plugin-Minimum-Java-Version` (avec tiret) sont
  silencieusement ignorées → le plugin ne se charge pas, JOSM ne montre qu'un
  warning « Version principale du greffon cog manquante ».
- `AbstractCachedTileSourceLayer` construit **réflexivement** le
  `CachedTileLoaderFactory(getCache(), getTileLoaderClass())` dans son
  constructeur : `CogTileLoader` DOIT avoir un constructeur public
  `(TileLoaderListener, ICacheAccess, TileJobOptions)` même si cache/options
  sont inutilisés. Ne pas passer d'état par ce constructeur (d'où le lecteur
  porté par `CogTileSource`).
- `ImageryInfo` rejette les types inconnus : ne pas faire
  `new ImageryInfo(name, url, "cog", …)`, utiliser le constructeur par défaut.
- `Logging.warn(msg, args)` applique `MessageFormat` : les apostrophes dans un
  message contenant `{0}` cassent le formatage (message brut affiché,
  exception perdue). Écrire les messages JOSM sans apostrophes ou les passer
  par `Logging.warn(exception)` séparément.
- `MapView.zoomTo(Bounds)` est hérité de `NavigatableComponent` ; après
  `addLayer`, le cadre de carte peut être en cours de création : le zoom est
  différé par un `javax.swing.Timer` (voir `AddCogLayerAction`).
- Journalisation du cycle de vie du plugin : `mapFrameInitialized` peut
  manquer la fenêtre déjà ouverte → ajout de l'action au menu avec retry.

## Exemple COG de référence

`cog-paris-3857.tif` (racine, 1,4 Mo, EPSG:3857 validé bout en bout).
Régénération :

```sh
gdalwarp -t_srs EPSG:3857 -te_srs EPSG:4326 -te 2.25 48.80 2.45 48.92 \
  -tr 10 10 -r bilinear -of COG -co COMPRESS=JPEG -co QUALITY=85 \
  "/vsicurl/https://sentinel-cogs.s3.us-west-2.amazonaws.com/sentinel-s2-l2a-cogs/31/U/DQ/2020/6/S2A_31UDQ_20200629_1_L2A/TCI.tif" \
  cog-paris-3857.tif
```

C'est le seul format COG « réaliste » couvert par les tests (JPEG à tables
externes) ; les COG rasterio (tables dans le flux) sont aussi acceptés.

## Tests GUI bout en bout (cette machine, optionnel)

Chercher d'abord à valider par `spike` + tests unitaires ; le GUI Xvfb est la
dernière étape. Raccourcis connus :

- Xvfb : `/tmp/opencode/xvfbroot/usr/bin/Xvfb :7 …` ; xdotool :
  `/tmp/opencode/xdotooloroot/usr/bin/xdotool` avec
  `LD_LIBRARY_PATH=/tmp/opencode/xdotooloroot/usr/lib/x86_64-linux-gnu` ;
  capture : `DISPLAY=:7 import -window root`.
- Lancement JOSM : mêmes `--add-exports` que `plugin` JOSM deb +
  `-Djosm.home=/tmp/opencode/josmhome -Djosm.plugins=cog -Dimagery.proj=EPSG:3857`
  + `--offline=ALL --debug` (le debug journalise `tileLoadingFinished`).
- `pkill -f` tue son propre shell si le motif matche la ligne de commande :
  écrire le motif en morceaux (`"josm-tes""ted.jar"`).

## Feuille de route

Phase 2 : reprojection par tuile (UTM, Lambert-93) via une matrice affine
maison (pas de PRJ complet). Phase 3 : panneau STAC (Panoramax). Publication
sur la liste des plugins JOSM (nécessite `Plugin-Mainversion` à jour et
javadoc/checkstyle JOSM si intégration upstream).
