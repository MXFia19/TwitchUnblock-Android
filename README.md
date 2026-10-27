# TwitchUnblock pour Android et Android TV

Version Android de [TwitchUnblock](https://github.com/MXFia19/TwitchUnblock) : regarder les directs, les VODs
et les clips de Twitch, avec le chat, sur téléphone, tablette et Android TV — une seule app.

> **En cours de développement.** Le portage se fait par séries ; voir l'état ci-dessous.

## Installer

1. Télécharge le dernier `TwitchUnblock-….apk` dans [Releases](https://github.com/MXFia19/TwitchUnblock-Android/releases).
2. Ouvre-le et autorise l'installation depuis cette source si Android le demande.

**Android TV / Google TV / Fire TV** : installe l'app *Downloader*, entre l'adresse de l'APK (lien du fichier dans
Releases), puis installe-le. L'app apparaît dans la liste des apps de la TV.

Les versions **TU Test** (pré-versions `test-N`) sont les builds de la branche de test : une app à part,
installable à côté de l'autre.

## État du portage

- **Série 1** (en cours) : directs (Luminous Asie et Europe, proxy albanais — chacun avec un serveur de
  secours : PerfProd Asie, l'autre serveur de nadeko, Luminous Europe 2 —, Twitch, Worker en secours),
  qualité Auto (suit le débit) ou fixe, qualité en données mobiles, VODs (passages coupés lisibles, reprise),
  clips, recherche (chaînes, catégories, liens collés), catégories, chaînes suivies (compte Twitch ou appareil),
  page de chaîne (VODs, clips), chat (emotes Twitch, 7TV, BTTV, FFZ, badges, historique récent, écrire avec
  un compte), connexion Twitch par code, réglages, journal, 4 langues (français, anglais, espagnol, russe).
  Interface Android TV : onglets, rangées à la télécommande, lecteur plein écran avec le chat, connexion par
  QR code.
- **Série 2** : image dans l'image, lecture en arrière-plan, minuterie, multi-live, téléchargements hors ligne,
  chat des VODs.
- **Série 3** : points de chaîne, prédictions, sondages, hype train, raids, drops, notifications (ntfy),
  signalements, annonces, sauvegarde, mises à jour, comptage.
- **Série 4** : partager l'instant (VOD à la position en cours, chaîne, clip) et créer un clip du direct
  (session web) depuis le menu ⋮ du lecteur ; « Continuer à regarder » sur l'accueil (rangée « Reprendre »
  sur la TV) ; téléchargements : taille estimée et espace libre avant de lancer, Wi-Fi seulement (le
  téléchargement attend le Wi-Fi), Reprendre / Réessayer dans la Bibliothèque ; notifications de live enrichies (vignette du direct, groupées par chaîne, « Regarder »
  et « Ne plus notifier cette chaîne »), cloche par chaîne (page de la chaîne, saisie du chat) et liste des
  chaînes notifiées dans les réglages ; chat : fiche d'une personne au toucher d'un message ou d'un pseudo
  (badges, création du compte, date de suivi, derniers messages, Mentionner, Masquer, Sa chaîne), fiche d'une
  emote (source, auteur), autocomplétion (`@` pseudos récents, `:` et 2 lettres pour les emotes).

## Construire

Le projet (Kotlin, Jetpack Compose, Media3) se construit avec la CI GitHub (`.github/workflows/build.yml`) :
JDK 17, Gradle 8.9, Android SDK 34. En local : Android Studio, ou `gradle :app:assembleRelease`. Les icônes
du lanceur et la bannière TV sont créées par la CI depuis `assets/icon.png`.

Les textes viennent des dictionnaires de l'app iPhone et de l'Apple TV :

```bash
node tools/i18n/gen-i18n.mjs ../TwitchUnblock
```

(textes propres à Android : `tools/i18n/android-strings.json`).

## Clé de signature

Sans clé, chaque build est signé avec une clé de débogage différente : il faut désinstaller l'app avant chaque
mise à jour. Pour signer toujours avec la même clé, crée-la une fois (et garde-en une copie : sans elle, plus
de mise à jour possible) :

```bash
keytool -genkeypair -v -keystore twitchunblock.jks -alias twitchunblock -keyalg RSA -keysize 2048 -validity 36500
```

puis ajoute ces secrets au dépôt (Settings → Secrets and variables → Actions) :
`ANDROID_KEYSTORE_B64` (le fichier en base64), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
(`twitchunblock`), `ANDROID_KEY_PASSWORD`.

## Licence

MIT, comme l'app iPhone.
