# Rétro Iso dans le navigateur

Cette version propose un environnement Wine 11 avec Boxedwine (page
`wine.html`), sans installation Microsoft Windows, et conserve le PC x86
v86 0.5.471 sur l'accueil. Le même site sert Android et Linux. Les calculs sont effectués
par le navigateur, GitHub Pages héberge les fichiers statiques, Google Drive
conserve les images et les sauvegardes transférées explicitement.

## État réel

- Moteur intégré, démarrage d'un PC et clavier vérifiés par un test utilisant
  le véritable moteur WebAssembly et les BIOS fournis au site.
- Écritures persistantes du disque vérifiées après un redémarrage complet.
- Bibliothèque, import d'images brutes, CD ISO, reprise de session,
  commandes tactiles, export de disque et diagnostic implémentés.
- Client Drive implémenté : liste paginée, téléchargements en flux,
  sauvegardes par transferts reprenables en blocs et restauration de PC.
  La connexion OAuth a été confirmée par l'utilisateur. Les nouveaux transferts
  d'installations Wine restent à vérifier avec les fichiers du Drive privé.
- Aucun Windows ni jeu commercial inclus. Le disque de diagnostic est du code
  original et ne constitue pas une preuve de compatibilité de Gangsters 2.
- L'interface n'a pas été vérifiée visuellement sur un Pixel 9a.

## Publication depuis GitHub

Dans **Settings → Pages → Build and deployment → Source**, sélectionner
**GitHub Actions**. Le workflow `Build and publish Retro Iso Web` construit,
teste puis publie `web/dist`. Son job `build` fournit aussi l'archive
`retroiso-web`, même si la configuration Pages bloque le job de publication.
Les modifications uniquement web ne reconstruisent plus l'ancien APK.

## Connexion Google Drive

Activer l'API Google Drive dans un projet Google Cloud et créer un client
OAuth de type **Web application**, avec l'origine exacte du site publié
dans les origines JavaScript autorisées. En mode test, ajouter le compte
utilisateur aux testeurs OAuth. Le scope Drive sert à accéder aux dossiers
et fichiers préexistants, y compris les ISO importés avant cette application.

L'identifiant client public peut être renseigné dans `config.json` pour
tous les appareils, ou dans les réglages de la page pour un seul navigateur.
Il ne faut jamais committer un secret client, un jeton ou un mot de passe.
Le jeton d'accès Google est temporaire et reste en mémoire. Une reconnexion
peut être demandée après expiration ; l'application ne conserve pas de
refresh token.

Le dossier Drive peut être choisi dans les réglages par son lien ou son ID.
Il n'est pas nécessaire de rendre le dossier public.

## Installer et jouer

### Jeux Windows avec Wine

1. Ouvrir **l'espace Wine** puis **Tester Windows et la sauvegarde**. Le petit
   programme Win32 original affiche `ECRITURE WINDOWS OK` et écrit sur C:.
2. Choisir **Enregistrer et fermer**, puis relancer ce test : `PERSISTENCE OK`
   doit apparaître. Une session neuve retrouve alors le fichier conservé.
3. Créer un profil par installation, le sélectionner, puis choisir son ISO
   local ou un ISO du dossier Drive privé. Ouvrir le profil, actualiser les
   programmes et lancer le programme d'installation sur D:.
4. Après installation, actualiser les programmes et lancer le jeu sur C:.
   Le programme choisi est conservé et son dossier sert de répertoire courant.
5. Enregistrer la partie dans le jeu, puis fermer le profil. **Sauvegarder sur
   Drive** envoie les fichiers modifiés du système Wine (registre inclus) dans
   une archive `.rwp`, puis publie un manifeste `.retroiso-wine.json`. Les fichiers
   du système de base proviennent du même environnement Wine versionné.
6. Sur l'autre appareil, restaurer le manifeste puis ouvrir le profil. L'ISO
   est téléchargé depuis sa référence Drive ; un CD local est d'abord envoyé
   lors de la sauvegarde. Les sauvegardes créent de nouvelles versions.

Wine est téléchargé dans OPFS une fois (99 456 246 octets). Chaque profil monte
son propre IDBFS avec un verrou exclusif par onglet. Les fichiers modifiés sont
copiés dans IndexedDB toutes les cinq secondes. La fermeture termine les
programmes Wine, attend wineserver, copie les fichiers puis détruit le moteur.
En cas d'échec d'enregistrement, la session reste ouverte et l'erreur est affichée.
La RAM n'est pas sauvegardée : le jeu se relance depuis son installation.
Les dernières écritures peuvent être perdues si l'onglet est tué avant copie.

Les CD ISO 9660 / Joliet sont extraits par blocs de 4 Mio dans un lecteur D:
en lecture seule. Ce lecteur ne reproduit pas les secteurs physiques, les
pistes audio ou toutes les protections CD. UDF seul, BIN/CUE et les fichiers
multi-extents ne sont pas pris en charge. Wine vise les programmes Windows x86 ;
les ISO de consoles nécessitent un moteur distinct. OpenGL est proposé comme
autre rendu, GDI comme premier essai 2D. Aucun succès de jeu sur Pixel n'est
déduit du diagnostic Windows. La mémoire nécessaire au moteur et à un CD de
500 Mo peut dépasser 1 Go ; les performances restent à mesurer sur le téléphone.

Le build 87 de Boxedwine et le système Wine 11 web v14 sont verrouillés par
SHA-256 dans `scripts/build-wine.mjs`. Les archives sont téléchargées pendant
le build et servies avec le site, pas committées dans le dépôt. Les licences,
sources et empreintes sont publiées avec le site. Le job `browser-smoke.mjs`
exécute le véritable moteur dans Chromium avec OPFS et IndexedDB, vérifie le
fichier Windows puis sa conservation après fermeture et rechargement. Les
captures et le résultat sont conservés dans l'artefact `browser-verification`.
Ce test de navigateur s'exécute uniquement sur GitHub Actions.

### PC v86 avec une image de système

1. Cliquer sur **Tester le moteur sans Windows** pour vérifier l'exécution.
   Appuyer sur Entrée pour écrire le marqueur, puis sur Échap pour redémarrer :
   `PERSISTENCE OK` doit apparaître. Fermer et relancer conserve le disque.
2. Pour Windows, importer un disque **brut** `.img`/`.raw` qui contient déjà
   Windows 95, 98 ou 2000, ou créer un disque vierge et monter le support
   d'installation Windows. Une disquette de démarrage peut être nécessaire.
   Les formats QCOW2 et VHD doivent être convertis avant import.
3. Démarrer le PC et monter le CD du jeu. L'installation se fait dans Windows,
   dans le disque virtuel : fichiers, registre et sauvegardes sont conservés.
4. **Enregistrer et fermer** arrête le moteur et conserve la RAM pour reprendre.
   Les écritures du disque sont, elles, enregistrées pendant l'exécution.
5. Avant d'exporter une installation vers un autre appareil, arrêter Windows
   proprement depuis son menu afin de vider ses caches, puis fermer le PC.
   **Sauvegarder sur Drive** exporte les disques complets et un manifeste
   `.retroiso.json`. Le disque contient notamment les sauvegardes des jeux.
6. Sur l'autre appareil, connecter Drive et cliquer **Restaurer ce PC** sur
   le manifeste. Le PC démarre à froid avec les fichiers et réglages restaurés.

Les sauvegardes Drive créent de nouveaux fichiers horodatés : aucune version
distante n'est écrasée automatiquement. Le manifeste n'est publié qu'après
le transfert réussi de tous les médias. Des transferts partiels peuvent laisser
des fichiers orphelins sur Drive, sans remplacer une sauvegarde complète.

## Stockage et limites

Les images de base sont stockées dans OPFS, les blocs modifiés dans IndexedDB.
Les lectures sont chargées à la demande avec un cache mémoire limité. Le
navigateur doit prendre en charge OPFS et Web Locks (Chrome récent conseillé).
Une écriture n'est confirmée à l'invité qu'après son enregistrement effectif.
Un verrou empêche deux onglets de modifier simultanément le même disque.

La reprise de RAM est liée à la version exacte du moteur et à la taille de RAM.
Une fois une session reprise, son ancienne capture est invalidée avant
l'exécution pour éviter une restauration RAM incompatible avec de nouvelles
écritures disque après un crash. Les données disque persistent même si le
navigateur se ferme brutalement, comme après une coupure d'alimentation ; les
données que Windows n'avait pas encore écrites peuvent être perdues.

Effacer les données du navigateur supprime les copies locales. Demander le
stockage persistant limite l'éviction, mais ne remplace pas une sauvegarde
Drive/export. Les exports complets utilisent temporairement de l'espace
supplémentaire. Les transferts Drive volumineux nécessitent une connexion
stable et un jeton non expiré ; aucune limite artificielle de 256 Mo n'est
appliquée par le client web.

v86 fournit VGA/SVGA et SoundBlaster 16. Ce n'est pas une carte 3D accélérée
compatible avec tous les jeux DirectX. La compatibilité et les performances
de chaque jeu, notamment Gangsters 2 sur Pixel 9a, doivent être vérifiées.

## Développement

```sh
cd web
npm ci
npm run build
npm test
```

Servir `web/dist` en HTTPS (ou localhost). Ne pas ouvrir `index.html` par
`file://`. Les tests moteur se lancent sous Node après la construction.
Les images Windows et les données personnelles ne vont jamais dans Git.
