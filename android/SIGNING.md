# Retro ISO — signature Android permanente

Les anciennes APK `app-debug.apk` étaient compilées sur des runners GitHub Actions
éphémères. Chaque runner pouvait régénérer un certificat debug différent,
ce qui empêchait la mise à jour Android par-dessus l'APK précédente.

## Configuration unique du dépôt

1. Générer et conserver **hors du dépôt** une clé Android au format PKCS12
   avec l'alias `retroiso`, et un fichier `password.txt` contenant le mot
   de passe du magasin et de la clé (identiques).
2. Créer un `tar.gz` contenant exactement `retroiso.jks` et `password.txt`.
3. Encoder le `tar.gz` en Base64 sans retour à la ligne.
4. Dans GitHub, ouvrir **Settings > Secrets and variables > Actions >
   New repository secret**, puis créer un secret nommé
   `RETROISO_SIGNING_BUNDLE` avec ce Base64 comme valeur.
5. Ouvrir **Actions > Build APK** et relancer l'exécution échouée, ou utiliser
   **Run workflow**. Le workflow valide le magasin et signe
   `app-debug.apk` avec ce certificat avant publication.

Le secret contient un certificat **privé**. Ne jamais ajouter la clé,
le mot de passe, le bundle Base64 ou une sauvegarde de ces fichiers aux
commits, issues, logs, releases, Pages ou fichiers accessibles publiquement.
Conserver une copie privée de la clé et du mot de passe ; sans elle,
les futurs builds ne peuvent pas être signés avec le même certificat.

## Mise à jour sur Android

L'application conserve l'identifiant `com.retroiso`. La toute première
installation signée avec la nouvelle clé nécessite de désinstaller les APK
précédentes **si leur signature diffère**, ce qui supprime aussi les
données privées Android du jeu (préfixe Wine, sauvegardes). Les futures APK
signées avec la même clé peuvent ensuite s'installer par-dessus.

Le `versionCode` Android doit continuer à croître à chaque mise à jour.

Le pipeline **échoue volontairement** si `RETROISO_SIGNING_BUNDLE` est
absent ou invalide, afin de ne pas republier une APK signée avec une clé
éphémère.
