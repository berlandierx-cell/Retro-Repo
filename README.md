# Retro ISO

Bibliothèque de vieux jeux Windows : ISO et sauvegardes sur Google Drive, lancement via Wine.

## Version navigateur

La nouvelle version dans [`web/`](web/README.md) utilise un PC x86 v86 dans
le navigateur, avec disques persistants et transfert des installations vers
Google Drive. Le même site est prévu pour Android et Linux. Le workflow
`Build and publish Retro Iso Web` construit, teste et publie sur GitHub Pages
une fois Pages configuré pour GitHub Actions.

Le démarrage, les écritures du disque et la reprise de session sont testés
avec un petit disque de diagnostic original. La compatibilité Windows/jeux
sur Pixel 9a et les appels Drive authentifiés restent à vérifier. Une image
Windows compatible et un identifiant OAuth Web sont nécessaires ; aucun
Windows ni jeu commercial n'est inclus.

- `ui/` : la page unique (PC et téléphone)
- `engine/` : moteur PC (Linux) : Drive, Wine, sauvegardes
- `profils/` : une recette par jeu (nom du fichier = nom du dossier Drive, en minuscules avec des tirets)
- `android/` : appli Android (page + extraction ISO ; moteur Wine à venir)

## Drive
`RetroGames/<Jeu>/jeu.iso` ; les sauvegardes vont dans `RetroGames/<Jeu>/saves/` (créé automatiquement).

## Mise en route PC
1. Installe `wine` et `python3`.
2. console.cloud.google.com : crée un projet, active « Google Drive API », écran de consentement
   (externe, ton adresse en testeur, puis « mettre en production »), identifiant OAuth « Application de bureau ».
3. Télécharge le JSON et place-le dans `~/.retroiso/client_secret.json`.
4. `./engine/run.sh` : la connexion Google s'ouvre une fois, puis la page s'affiche.

Ne commite jamais `client_secret.json` ni `token.json`.
