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
  un compte), connexion Twitch par code, réglages, journal, 7 langues (français, anglais, espagnol, russe ; italien, japonais et chinois traditionnel
  traduits automatiquement, comme sur l'iPhone).
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

- **Série 5** (menée en cinq parties en parallèle) :
  - *Lecteur* : vitesse de 0,25× à 2× (menu ⋮ seulement, la roue dentée ne la propose plus ; retenue pour les VODs, les clips repartent à 1×, un direct
    reculé est rattrapé à cette vitesse jusqu'au bord) ; double-tap à gauche ou à droite pour reculer ou avancer de
    10 s (les touchers s'additionnent) ; remplir l'écran en paysage (Réglages → Lecteur) et zoom à deux doigts
    jusqu'à 4× ; heure réelle des archives (avec les commandes, sur la barre, ⋮ → Aller à une heure) ; toucher le
    titre ouvre une fiche (titre en entier, catégorie, spectateurs, durée ou date, chaîne) ; passages coupés en
    orange sur la barre, avec « Passer » ; compresseur audio comme sur le site ; plusieurs sons dans le multi-live
    (haut-parleur de chaque tuile, appui long pour le volume) ; qualité de chaque tuile comme sur l'iPhone
    (1080p seule, 720p à deux, 480p au-delà, bornée par la qualité préférée et celle des données mobiles) ; les tuiles
    restent, en pause, pendant la connexion ouverte depuis le chat.
  - *Direct* : un direct bloqué dans ton pays est dit tel quel ; pauses pub de la chaîne expliquées (bandeau,
    compte à rebours, son coupé le temps de la pause, tuiles du multi-live comprises) ; image verticale des
    directs en Dual Format (bouton sur l'image, choix retenu) ; barre de toute la diffusion quand l'enregistrement
    existe (avant la fenêtre du direct, l'enregistrement prend le relais ; au bout, retour au direct) ; Ma source
    (ton serveur luminous-ttv, essayé en premier) ; qualités supérieures (1440p…) par ton compte quand il regarde
    sans pub ; diffusion d'un direct sur un Chromecast (menu ⋮ ; une VOD ou un clip ouvert sur le
    téléphone y met fin).
  - *Chat* : emotes favorites (appui long ou fiche), en tête du sélecteur et de l'autocomplétion ; emotes masquées
    affichées comme le mot tapé (Réglages → Chat → Emotes masquées pour les réafficher) ; banni d'un chat, il reste
    lisible sans le compte (« Réessayer avec mon compte ») ; chat synchronisé avec la vidéo (pastille ⏱ : toucher
    pour le temps réel, appui long pour ±1 s) ; modes du salon au-dessus du champ, règles à accepter, refus de
    Twitch expliqués ; en paysage, chat en colonne, posé sur l'image (gauche, droite, coin) ou replié, largeur à la
    poignée ; zone d'envoi masquable ; tailles (espace, badges, emotes, 12/24 h) avec aperçu ; couleur du pseudo ; annonces des modérateurs
    encadrées de leur couleur ; le chat des VODs suit aussi les tailles du chat, l'heure y est la position dans la VOD.
  - *Navigation* : chaînes épinglées en tête des suivies (appui long, menu ⋮ de la chaîne) ; lives en liste ou en
    grille avec la durée du direct ; « Continuer à regarder » (appui long : reprendre, retirer) ; retour en haut en
    touchant l'onglet affiché ; catégories suivies (♡ sur leur page, rangée de l'accueil avec celles du compte
    Twitch) ; page d'une chaîne : Planning et À propos ; onglet Découvrir (un direct ou un clip par écran, aperçu
    muet, son au toucher) ; Personnaliser l'app (ordre et onglets masqués, onglet au lancement, parties de
    l'accueil) ; grands écrans (plus de colonnes, onglets à gauche) ; widget des chaînes suivies en direct et
    raccourcis de l'icône ; retour d'une page : l'accueil, la recherche, une chaîne ou une catégorie gardent leur
    défilement, la page d'une chaîne garde son onglet et sa période sans rien relire.
  - *App* : thèmes (7 couleurs d'accent, fonds sombre, noir OLED et ardoise) et icône de l'app au choix (celles de
    l'iPhone) ; changer de compte sans se reconnecter (les autres comptes restent gardés avec leur session web et
    leur historique ; à la déconnexion, l'historique reste au compte parti, repris s'il revient, jamais envoyé sur le
    compte suivant) ; réglages synchronisés avec le compte Twitch, partagés avec le site et l'app iPhone ; session
    web vérifiée au lancement et à la connexion ; signalements signés avec le compte Twitch ou un pseudo ; option
    « Sans compte Twitch » qui masque les invitations à se connecter.
  - Sauvegarde : ces réglages sont dans le fichier, sous les noms du site et de l'iPhone quand ils les ont, avec
    « Continuer à regarder » (positions des VODs), les réglages des drops, la synchro de l'historique et la source du
    direct.

- **Corrections du 5 novembre 2026** (analyse complète de l'app, menée en cinq parties) : 73 bugs corrigés, dont
  les notifications (permission d'Android 13 demandée pour les drops et expliquée pour les directs, plus de rafale ni
  de doublons, ntfy coupé pour de bon), le chat (annonces, messages supprimés et /clear respectés, emotes 7TV, BTTV et
  FFZ dans l'historique, gains de prédiction hors du suivi des points), les suivis gardés pendant une panne, le retour
  d'une page sans perte du défilement, l'historique gardé au compte qui se déconnecte, la reprise des VODs sur Android
  TV et les textes complets en italien, japonais et chinois traditionnel. Effacer l'historique des VODs ou le suivi
  des points demande confirmation.

- **Série 6** (fonctions encore absentes après l'analyse du 5 novembre, menée en six parties en parallèle) :
  - *Lecteur* : mini-lecteur (Retour, glisser l'image vers le bas ou ⌄ réduisent le lecteur en barre au-dessus des
    onglets ; un toucher le remet en grand sans recharger ; une fiche chaîne ou la connexion ouverte depuis le lecteur
    garde la vidéo en bas) et « PiP en quittant le lecteur » ; son seul en arrière-plan ; « Direct · −45 s » et latence
    affichée ; faible latence côté lecteur (jamais sous 4 s de retard, par petits sauts) ; reprise seule au retour du
    réseau ; écran de fin de direct (raid à suivre, Voir les VODs) ; minuterie (autre durée, temps restant, +15 min,
    multi-live) ; chapitres sur la barre ; haut-parleur et Recharger.
  - *Média* : sources du direct dans l'ordre choisi (Réglages → Lecteur) et choisies depuis le menu de qualité ;
    « Source » avec sa résolution ; son d'origine des passages coupés tant que Twitch l'a ; clips : « Voir dans la
    VOD » et « Télécharger le clip » ; téléchargement d'une partie (d'ici, 10 min, d'un instant à un autre, par
    chapitre), qualité préférée d'abord, export en un fichier, reprise au retour du réseau app fermée (par une
    notification depuis Android 12) ; multi-live avec des VODs, ⇄, ouvrir une tuile dans le lecteur, Réessayer /
    Revoir, relance au retour du réseau, chat en paysage, lien à partager, appui long sur une carte pour l'ajouter.
  - *Chat* : répondre à un message (fil, copier) ; sélecteur d'emotes en onglets (favorites, Mes emotes, la chaîne
    avec les emotes d'abonné verrouillées) ; fiches complètes des emotes, badges et GIF, aussi dans le chat des VODs
    et des clips ; BetterTTV en direct, FFZ animées ; menu ⋯ (reconnecter, recharger, présents, commandes des bots,
    chat seul) et échecs de connexion dits ; carte des drops ; raids rejoints avec la session web (bonus) ; minute
    regardée relayée par le Worker quand spade est bloqué ; points « en ce moment » ; bandeaux rangés en pastilles ;
    bouton suivre au-dessus du chat ; chat d'origine des clips ; premier message mis en avant, historique marqué ;
    correction automatique du clavier réglable.
  - *Navigation* : recherche en onglets (Chaînes, Catégories, En direct, VODs, avec leurs nombres), 10 dernières
    recherches, masqués signalés ; page d'une chaîne : Temps forts, Playlists avec « Tout lire », filtre des VODs,
    commandes des bots, suivi refusé expliqué, onglets réglables ; catégories : langue et tri ; accueil : Top « ma
    langue » ou « Monde », cartes « Se connecter » et « Session web », ordre des parties ; tirer pour actualiser ;
    listes longues par le compte Twitch quand Twitch refuse la suite ; Top complété ; streamers récents retirables ;
    tags sous chaque live.
  - *App* : Worker perso vérifié et « Tout passer par ce Worker » ; comptage effacé côté serveur ; « Reprendre les
    VODs » ; avis dans l'app (drops, réponses aux signalements, relues chaque minute et en arrière-plan) et signaler
    depuis le lecteur ; journal exportable (traduit en anglais) ; import « Tout » ou « Chaînes seulement » avec un
    résumé ; synchro du format de l'heure, de la zone d'envoi et de la personnalisation ; twitch.tv/activate dans
    l'app pour changer de compte ; visite guidée et journal des modifications (Réglages → À propos).
  - *Android TV* : bandeau des pauses pub ; chat posé sur l'image, place retenue par chaîne ; Suivies relues
    seules ; masquer une chaîne ou une catégorie (appui long) et les réafficher ; « Voir la chaîne » et touche
    Lecture ; clips par période et collections ; qualité retenue ; rangée « Reprendre » réglable. Icône adaptative,
    avec sa version monochrome pour les icônes à thème d'Android 13.
  - Sauvegarde : les nouveaux réglages y sont (ordre des sources, son d'origine, réglages du chat, langue des
    catégories, chat et rangée « Reprendre » de la TV, lecteur).

## Construire

Le projet (Kotlin, Jetpack Compose, Media3) se construit avec la CI GitHub (`.github/workflows/build.yml`) :
JDK 17, Gradle 8.9, Android SDK 34. En local : Android Studio, ou `gradle :app:assembleRelease`. Les icônes
du lanceur et la bannière TV sont créées par la CI depuis `assets/icon.png`.

Les textes viennent des dictionnaires de l'app iPhone et de l'Apple TV :

```bash
node tools/i18n/gen-i18n.mjs ../TwitchUnblock
```

(textes propres à Android : `tools/i18n/android-strings.json`, et un `android-strings-<partie>.json` par partie de l'app).

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
