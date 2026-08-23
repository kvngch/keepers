# Keepers

Coffre-fort d'informations personnelles pour Android : notes, documents, factures, reçus et
captures d'écran, avec une approche privacy-first. Toutes les données et tous les traitements
d'indexation restent sur l'appareil. L'application ne demande aucune permission réseau et
n'embarque aucun SDK tiers de mesure.

## Installation

Télécharger le dernier APK depuis la page
[Releases](https://github.com/kvngch/keepers/releases), puis l'installer sur un appareil
Android 8.0 ou plus récent (arm64, l'installation de sources inconnues doit être autorisée).
Chaque release inclut l'empreinte SHA-256 de l'APK.

Pour recevoir les mises à jour automatiquement, ajouter le dépôt dans
[Obtainium](https://github.com/ImranR98/Obtainium) avec l'URL `https://github.com/kvngch/keepers`.

## Fonctionnalités

- Recherche plein texte instantanée (index FTS local, insensible aux accents) sur les
  titres, résumés et contenus
- Filtres par type (notes, images, PDF), par période, tri, regroupement par mois
- Scanner de documents (recadrage automatique, multi-pages) avec repli sur l'appareil
  photo, import multiple de fichiers, note rapide, partage vers Keepers depuis
  n'importe quelle application
- OCR on-device (ML Kit Text Recognition, modèle embarqué dans l'APK) sur les captures
  et les images importées; PDF numériques et scannés indexés page par page (10 premières)
- Extraction automatique des montants, dates et IBAN; les échéances détectées déclenchent
  une notification locale 7 jours avant
- Visionneuse interne (images zoomables, PDF, texte), miniatures sur les cartes,
  édition des notes
- Corbeille avec purge automatique (délai configurable), sélection multiple par appui long
- Sauvegarde portable chiffrée par mot de passe (export et restauration), et sauvegarde
  automatique hebdomadaire vers un dossier choisi (4 dernières conservées)
- Écran de réglages : délai de verrouillage, rétention de la corbeille, pages PDF
  indexées, scanner de documents
- Widget de capture et raccourcis d'icône (appui long)
- Thème Material 3 complet en mode clair et sombre (fonds mats pensés pour OLED),
  accents vert minéral, métadonnées en police monospace

## Confidentialité et sécurité

- Base de données chiffrée par SQLCipher (AES-256) et fichiers stockés chiffrés (AES/GCM),
  clés protégées par une clé non exportable du Keystore Android
- Verrouillage par biométrie ou code de l'appareil à l'ouverture
- Captures d'écran bloquées, contenu masqué dans les applications récentes
- Métadonnées de géolocalisation EXIF retirées des images à l'import
- Pas de permission Internet dans le manifeste : rien ne quitte l'appareil. L'OCR utilise
  la variante bundled de ML Kit, modèle inclus dans l'APK. Le scanner de documents est
  fourni par Google Play services quand il est disponible (traitement on-device), sinon
  l'appareil photo est utilisé.
- L'ingestion est reprise automatiquement si l'application est interrompue en cours
  d'indexation (WorkManager)

## Build

```bash
./gradlew assembleDebug
```

JDK 17 et le SDK Android 35 sont requis. Chaque push sur `main` passe les tests unitaires,
la compilation debug et les tests instrumentés sur émulateur (migration de base, export et
restauration chiffrés). La release signée est produite par le workflow GitHub Actions au
push d'un tag `v*`.

```bash
./gradlew test                        # tests unitaires
./gradlew connectedDebugAndroidTest   # tests instrumentés (appareil ou émulateur requis)
```
