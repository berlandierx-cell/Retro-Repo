# Retro ISO

Bibliothèque de vieux jeux Windows : ISO et sauvegardes sur Google Drive, lancement via Wine.

## Version navigateur

La version dans [`web/`](web/README.md) propose un espace Wine/Boxedwine pour
les programmes Windows x86, sans image Microsoft Windows, et conserve le PC
v86 pour les images de systèmes anciens. Les installations sont persistantes
et peuvent être transférées sur Google Drive. Le même site sert Android et Linux. Le workflow
`Build and publish Retro Iso Web` construit, teste et publie sur GitHub Pages
une fois Pages configuré pour GitHub Actions.

Le PC v86 est vérifié par un disque de diagnostic original. L'espace Wine
dispose d'un programme Win32 original qui écrit un fichier et affiche le
résultat : le job Chromium doit vérifier l'écriture et le redémarrage avant
la publication. La compatibilité de Gangsters 2 et les performances sur Pixel
9a restent à valider. La connexion Drive a été confirmée par l'utilisateur ;
les nouveaux transferts d'installations Wine restent à vérifier en situation
réelle. Aucun jeu commercial ni installation Microsoft Windows n'est inclus.

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
