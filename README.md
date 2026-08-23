# Keepers

Coffre-fort d'informations personnelles pour Android : notes, documents, factures, reçus et
captures d'écran, avec une approche privacy-first. Toutes les données et tous les traitements
d'indexation restent sur l'appareil. L'application ne demande aucune permission réseau et
n'embarque aucun SDK tiers de mesure.

## Installation

Télécharger le dernier APK depuis la page
[Releases](https://github.com/kvngch/keepers/releases), puis l'installer sur un appareil
Android 8.0 ou plus récent (l'installation de sources inconnues doit être autorisée).
Chaque release inclut l'empreinte SHA-256 de l'APK.

## Fonctionnalités

- Recherche en langage naturel sur les titres, résumés et contenus, exécutée localement
- Ingestion par capture photo, import de fichier ou note rapide (bouton flottant)
- Extraction et indexation locales du texte des fichiers importés
- OCR on-device (ML Kit Text Recognition, modèle embarqué dans l'APK) sur les captures
  photo et les images importées : le texte reconnu devient cherchable
- Indexation des PDF, numériques comme scannés : les pages (10 premières) sont rendues
  par le moteur PDF natif d'Android puis passées dans le même OCR local
- Cartes de documents avec résumé sur deux lignes, métadonnées (date, format, taille) et
  badge d'indexation
- Thème Material 3 complet en mode clair et en mode sombre (fonds mats pensés pour OLED),
  accents vert minéral, métadonnées en police monospace

## Confidentialité

Le stockage se fait dans l'espace privé de l'application (base Room et fichiers internes).
Rien ne quitte l'appareil : pas de télémétrie, pas de synchronisation, pas de permission
Internet dans le manifeste. L'OCR utilise la variante bundled de ML Kit : le modèle est
inclus dans l'APK et la reconnaissance s'exécute entièrement sur l'appareil, sans
téléchargement ni Google Play Services.

## Build

```bash
./gradlew assembleDebug
```

JDK 17 et le SDK Android 35 sont requis. La release signée est produite par le workflow
GitHub Actions au push d'un tag `v*`.
