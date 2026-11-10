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
- V1 n'accepte que **EPSG:3857 et EPSG:4326**. Depuis la phase 2, la
  reprojection par tuile est active pour les CRS listés dans `crs/Crs`
  (4326, 3857, UTM WGS84 32601-32660 / 32701-32760, Lambert-93 2154) ;
  depuis la phase 2.1 aussi les **GeoKeys custom (32767)** dont les
  paramètres décrivent un modele connu (geographique, Transverse Mercator,
  Lambert conique conforme 1SP/2SP — cf. `Crs.fromCustom`). Tout autre
  CRS ou methode doit produire une erreur explicite (pas de reprojection
  silencieuse ni de supposition de SRID). Pas de parsing PROJ.4/WKT :
  seules les clefs GeoTIFF sont lues (choix assume, phase 2.2 = proj4j).

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
  `TileDecoder` (NONE/Deflate/PackBits/LZW/JPEG). `CustomCrs` porte les
  GeoKeys bruts (doubles du locator 34736 inclus) quand le SRID est 0 ou
  32767 ; `CogTileSource` resout la projection des sa reception du
  `CogInfo` et garde l'erreur pour l'afficher au chargement de tuile.
- `layer/` : intégration JOSM. `CogImageryLayer` extends
  `AbstractCachedTileSourceLayer<CogTileSource>` ; `CogTileSource` est une
  source TMS sans URL (synthèse à la volée) qui **porte le `CogReader`** ;
  `CogTileLoaderJob` récupère le lecteur via `tile.getSource()`.
- `AddCogLayerAction` : dialogue URL + ouverture + zoom vers l'emprise.
- `crs/` : projections maison (Snyder / USGS PP 1312, précision sub-mm en
  avant, validée contre GDAL/PROJ dans `ProjectionTest`). Interface
  `Projection` (project/unproject), registre/cache `Crs.forEpsg`.
  `CogComposer.composeWarp` : grille de controle 9x9 projetee exactement
  (3857 -> lat/lon -> coordonnees modeles), interpolation bilineaire par
  pixel de sortie, echantillonnage bilineaire ARGB conscient de l'alpha.
  Les voies lineaires 3857/4326 de `compose` sont conservees (non-regression).
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
- **Tri de pyramide en int** : l'aire `width*height` d'un niveau déborde
  `int` dès 2^31 pixels (COG PIX4Dmatic 131072x98304 = 3x2^32 → aire 0, et
  l'overview 65536x49152 = 3x2^30 → négative) : les overviews passaient
  avant le niveau 0 et le garde `flags&1` criait « IFD de pleine resolution
  absent » sur des fichiers pourtant conformes. Utiliser `Long.compare`
  (`TiffParser.sortLevelsByAreaDesc`, test non-regression dedie).

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

## Pièges reprojection (phase 2, déjà rencontrés)

- `LambertConformalConic2SP` : le parametre `lat0` du constructeur masque le
  champ radians `this.lat0` ; utiliser `t(this.lat0)` pour `rho0` (sinon NaN).
- Convention GeoTIFF : le pixel k couvre [k, k+1[ et son CENTRE vaut k+0.5
  (`col = (x - originX) / scaleX`, echantillonner en `col - 0.5`). Un motif
  de test bilineaire centre sur les entiers fait un decalage systematique
  d'un demi-pixel.
- Ligne 0 d'une image Java = Nord = `bbox.maxY` : la grille de controle du
  warp doit demarrer a `maxY` (sinon sortie retournee verticalement ; un
  test qui reproduit la meme convention d'erreur ne la detecte pas).
- References GDAL en Python : `srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)`
  (methode `SetAxisMappingStrategy`, pas `SetAxisMapping`) pour obtenir
  l'ordre lon/lat, sinon les transformees sont fausses.
- Series de Snyder 6e ordre : ~3 mm d'erreur a 5 degres du meridien central
  (tolerances de test elargies pour les cas hors fuseau UTM).

## Pièges GeoKeys custom (phase 2.1, déjà rencontrés)

- **Portee des constructeurs `crs/`** : un parametre « degres » qui porte le
  meme nom qu'un champ en radians (`lat0`, `lon0`…) masque le champ ; les
  projections LCC/TM etaient fausses de ce fait. Convention : parametres
  suffixes `Deg` et `this.` explicite partout.
- **Reconciliation 3074/3075** : GeoKey 3074 (TransformationCoordinnees) =
  32767 chez GDAL ; le code de methode reel est en 3075. Les valeurs TM=1 et
  LCC2SP=8 sont partagees entre les deux tables ; seuls les codes Lambert 1SP
  different (3074=9 vs 3075=14). Albers (3075=11) N'EST PAS supporte : doit
  etre refuse explicitement (fixture `custom_albers_cog.tif`).
- **Convention PROJ du facteur d'echelle Lambert** : GeoKey 3092 (k0) absent
  vaut 1,0 ; quand il est present, PROJ l'applique a TOUT le rayon rho
  (pas seulement rho0), donc (lat0,lon0) tombe exactement sur (FE,FN) et la
  scale locale a lat0 vaut k0. `LambertConformalConic2SP` fait de meme
  (verifie contre GDAL : 1SP k_0=0.99987731, (45N,5E) -> (1000000,2000000)).
- **Ellipsoide** : priorite aux doubles 2057 (axe) / 2059 (aplatissement),
  sinon code 2056 (table `Ellipsoid.forGeoKeyCode`) ; premier meridien 2061
  (double) supporte en geographique uniquement ; unites lineaires 3076 !=
  9001 via l'adaptateur `Crs$Scaled` ; unites angulaires != degres refus.
- **Écriveur de test `TestTiffWriter`** : `geoBlockSize()` doit rendre la
  taille EXACTEMENT ecrite par `writeExt` (72 + 2*nKeys + 8*nDbl +
  CITATION+1 en custom, 96 sinon) — un ecart de 7 octets decales tous les
  TileOffsets (symptome : « Taille de tuile attendue N octets, recu 0 »).
  Les clefs GeoKeys sont des `int[]` (le locator 34736 depasse short signe)
  et `write()` verifie que l'en-tete NumberOfKeys correspond exactement aux
  entrees presentes.
- Les COG ecrits par GDAL en custom peuvent avoir 2061=0 meme si lon_0 etait
  non nul dans la chaine PROJ generatrice : se fier aux GeoKeys lus, pas a
  l'intention de generation (cas du fixture longlat).
- **Hybride « code enregistre + parametres »** (exports PIX4Dmatic, ex.
  COG_BayonneSudEst.tif en EPSG:3943/Lambert-43) : 3072 porte un code EPSG
  connu de GDAL mais inconnu de notre registre, et 3074=32767 garantit que
  TOUS les parametres de methode sont presents en DOUBLE. Regle : `TiffParser`
  garde le `CustomCrs` des que 3074=32767, et `Crs.resolve(code, custom)`
  tente le registre EPSG d'abord puis les parametres (jamais l'inverse, un
  code au registre gagne). Sans 3074=32767, un 3072 enregistre reste refuse
  (« clefs custom incoherentes ») : pas de parametres exploitables.
  L'ecriveur de test aligne le bloc custom sur la PRESENCE de customKeys,
  pas sur la valeur de crs (sinon 2 entrees IFD manquees, decalement de
  24 octets de tout le bloc geographique).

## Exemple COG de référence

`cog-paris-3857.tif` (racine, 1,4 Mo, EPSG:3857) et `cog-paris-l93.tif`
(750 ko, EPSG:2154, même scène). Régénération :

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

C'est le seul format COG « réaliste » couvert par les tests (JPEG à tables
externes) ; les COG rasterio (tables dans le flux) sont aussi acceptés.

### Fixtures « GeoKeys custom » (phase 2.1)

`custom_lcc_cog.tif`, `custom_tmerc_cog.tif`, `custom_longlat_cog.tif`
(racine, générés par GDAL, donc GeoKeys 32767 + doubles 34736 réalistes) et
`custom_albers_cog.tif` (methode 3075=11, doit etre **refuse**). Ancres de
reference GDAL dans `CogCustomWarpTest`/`CustomCrsTest`. Régénération :

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
- Vérification des liaisons sans GUI : `/tmp/opencode/LinkCheck.class`,
  lancer `java -cp /usr/share/josm/josm.jar:build/libs/cog.jar:/tmp/opencode
  LinkCheck build/libs/cog.jar` (le jar teste doit etre AUSSI dans le classpath).

## Feuille de route

Phase 2 (faite) : reprojection par tuile (UTM, Lambert-93) via les formules
`crs/` maison (pas de PRJ complet).
Phase 2.1 (faite) : GeoKeys custom 32767 maps sur les classes `crs/`
existantes (`Crs.fromCustom`), sans nouvelle mathematique de projection.
Phase 2.2 (envisagee) : backend proj4j pour les modeles non couverts
(Albers, Mercator oblique, datum non-WGS84…) — casserait la contreainte
« aucune dependance », a arbitrer.
Phase 3 : Publication sur la liste des plugins JOSM (nécessite `Plugin-Mainversion` à jour et
javadoc/checkstyle JOSM si intégration upstream).
