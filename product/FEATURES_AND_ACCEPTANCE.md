# FileSend - Fonctionnalités et critères d'acceptation

> Version 1 · 2026-11-08

## 1. Priorités

| ID | Fonctionnalité | Priorité | Justification |
|---|---|---|---|
| F1 | Découverte des appareils proches | MVP | Sans elle, pas de destinataire |
| F2 | Envoi de fichiers et dossiers | MVP | Valeur centrale |
| F3 | Réception avec consentement | MVP | Pas de réception sans accord, sécurité de base |
| F4 | Notifications d'état (envoi, envoyé, reçu, échec) | MVP | Exigé : l'interface est surtout faite de notifications |
| F5 | Interface verre, thèmes clair/sombre, légère | MVP | Exigé par l'utilisateur |
| F6 | Appareils de confiance (acceptation auto) | V2 | Confort, après validation du MVP |
| F7 | Réglages (nom, dossier, démarrage) | V2 | Défauts suffisants au MVP |
| F8 | Réception Android en arrière-plan | V2 | Coût batterie, voir H3 |
| F9 | Reprise après coupure | V2 | Complexité |
| F10 | Comptes, cloud, Internet, iOS/macOS/Linux | Hors périmètre | Contredit "local simple" |

## 2. Parcours utilisateurs

### P1 - Envoyer (Windows vers Android, ou inverse)

1. Windows : glisser fichiers/dossiers sur la fenêtre, ou clic droit "Envoyer vers > FileSend". Android : "Partager" depuis une autre app, ou bouton "+" de l'écran principal.
2. Les appareils proches s'affichent ; toucher ou cliquer l'appareil cible (si un seul appareil, un seul geste).
3. Notification "Envoi à <nom>" avec progression.
4. Notification "Envoyé à <nom>".

**Résultat** : les fichiers sont chez l'autre appareil, sans compte ni câble.

### P2 - Recevoir

1. Notification "<nom> veut envoyer 3 éléments (12 Mo)" avec Accepter et Refuser.
2. Accepter : progression, puis notification "Reçu de <nom>" ; toucher ouvre le dossier.
3. Refuser ou ignorer 60 s : rien n'est enregistré, l'expéditeur est informé.

## 3. Écrans et surfaces

| ID | Surface | Rôle | Vide | Chargement | Erreur | Succès |
|---|---|---|---|---|---|---|
| E1 | Écran principal (fenêtre Windows / écran Android) | Liste d'appareils proches + zone de dépôt | "Aucun appareil. Même Wi-Fi ?" | Indicateur discret de recherche | Bandeau "Pas de réseau" | Tuile de l'appareil avec coche brève |
| E2 | Notifications (Windows : petite fenêtre en verre de l'application, créée à la demande ; Android : notifications système) | Demande, progression, résultat | Sans objet | Barre de progression | "Échec : <raison>" | "Envoyé à" / "Reçu de" |
| E3 | Icône de zone de notification Windows | Ouvrir, quitter | Sans objet | Sans objet | Sans objet | Sans objet |

Textes : mots uniques ou phrases de moins de 6 mots. Aucun texte d'aide permanent.

## 4. Spécifications du MVP

### F1 - Découverte

- **Objectif** : voir et atteindre les appareils FileSend du même réseau sans configuration.
- **Comportement** : chaque appareil s'annonce (nom, type, port, empreinte) et écoute les annonces. Si le multicast échoue, repli par sondage des adresses du sous-réseau (voir TECHNICAL_ARCHITECTURE).
- **Règles** : l'appareil n'apparaît pas dans sa propre liste ; nom par défaut = nom de l'appareil.
- **Critères** :
  - **AC-F1-1** - Étant donné deux appareils sur le même réseau avec l'app ouverte, quand la 2e démarre, alors chacun voit l'autre dans sa liste en moins de 5 s.
  - **AC-F1-2** - Étant donné un appareil listé, quand son app est fermée, alors il disparaît de l'autre liste en moins de 15 s.
  - **AC-F1-3** - Étant donné un réseau où le multicast est désactivé mais l'accès direct possible, quand les deux apps tournent, alors l'appareil est trouvé par le repli en moins de 20 s.
  - **AC-F1-4** - Étant donné aucun appareil proche, quand E1 s'affiche, alors le texte "Aucun appareil. Même Wi-Fi ?" est visible et la zone de dépôt reste active.
- **Vérification** : AC-F1-1, 3 automatique (deux instances locales, multicast simulé coupé) et manuelle ; AC-F1-2, 4 mixte.

### F2 - Envoi de fichiers et dossiers

- **Objectif** : transférer un ou plusieurs fichiers ou dossiers vers un appareil choisi.
- **Comportement** : l'expéditeur propose la liste (noms relatifs, tailles) ; si le destinataire accepte (F3), les fichiers sont envoyés en flux, un à un.
- **Règles** : transfert chiffré (TLS) ; flux sans charger un fichier entier en mémoire ; l'arborescence des dossiers est conservée ; aucun écrasement.
- **Erreurs** : refus, destinataire disparu, réseau coupé, fichier illisible, disque plein côté receveur.
- **Critères** :
  - **AC-F2-1** - Étant donné un fichier de 10 Mo envoyé et accepté, quand le transfert se termine, alors le fichier reçu porte le même nom et le même SHA-256.
  - **AC-F2-2** - Étant donné un dossier `photos/` contenant `a.jpg` et `v/b.jpg`, quand il est envoyé, alors le receveur obtient `photos/a.jpg` et `photos/v/b.jpg` avec mêmes SHA-256.
  - **AC-F2-3** - Étant donné un fichier `a.txt` déjà présent chez le receveur, quand un autre `a.txt` arrive, alors il est enregistré `a (1).txt` et l'original reste intact.
  - **AC-F2-4** - Étant donné une demande contenant un chemin `../x`, un chemin absolu, un nom réservé Windows (`CON`) ou un caractère interdit, quand le receveur la traite, alors elle est refusée ou assainie et rien n'est écrit hors du dossier de réception.
  - **AC-F2-5** - Étant donné un transfert en cours, quand le réseau est coupé ou l'expéditeur annule, alors le receveur supprime le fichier partiel et les deux côtés affichent "Échec : interrompu" en moins de 15 s (délai d'inactivité de 10 s : une coupure silencieuse ne se distingue d'un silence qu'après ce délai ; mesuré à 10,7 s côté PC le 2026-11-08, le critère initial de 10 s était trop juste).
  - **AC-F2-6** - Étant donné un fichier de 2 Go, quand il est envoyé, alors la mémoire du processus reste sous 150 Mo (cible H).
- **Vérification** : automatique (tests d'intégration, deux instances) ; AC-F2-6 mesure manuelle ; essai réel Windows/Android (M1).

### F3 - Réception avec consentement

- **Objectif** : ne rien recevoir sans accord explicite.
- **Comportement** : une demande entrante déclenche une notification avec expéditeur, nombre d'éléments, taille totale, Accepter/Refuser. Accepter démarre le téléchargement vers le dossier par défaut.
- **Règles** : dossier par défaut `Téléchargements/FileSend` ; délai de réponse 60 s ; une demande à la fois par expéditeur ; le port n'accepte que du TLS.
- **Critères** :
  - **AC-F3-1** - Étant donné une demande entrante, quand elle arrive, alors la notification affiche nom de l'expéditeur, nombre d'éléments et taille, et aucun octet n'est écrit sur le disque avant Accepter.
  - **AC-F3-2** - Étant donné une demande, quand l'utilisateur choisit Refuser, alors rien n'est enregistré et l'expéditeur voit "Refusé".
  - **AC-F3-3** - Étant donné une demande sans réponse, quand 60 s s'écoulent, alors elle est traitée comme un refus et l'expéditeur voit "Refusé".
  - **AC-F3-4** - Étant donné une demande acceptée, quand le transfert finit, alors les fichiers sont dans `Téléchargements/FileSend` et toucher la notification "Reçu" ouvre ce dossier.
  - **AC-F3-5** - Étant donné une connexion en HTTP clair sur le port d'écoute, quand elle est tentée, alors elle est refusée sans réponse applicative.
- **Vérification** : AC-F3-1 à 3 et 5 automatiques (logique) + manuels pour les notifications ; AC-F3-4 manuel.

### F4 - Notifications d'état

- **Objectif** : l'interface principale du produit.
- **Comportement** : demande, progression en pourcentage, "Envoyé à <nom>", "Reçu de <nom>", "Échec : <raison>".
- **Règles** : sur Windows, la notification est une fenêtre propre car les notifications système de Tauri n'ont pas de boutons d'action (D5) ; raisons possibles = Refusé, Introuvable, Interrompu, Disque plein ; une notification par transfert, mise à jour sur place.
- **Critères** :
  - **AC-F4-1** - Étant donné un transfert en cours, quand il progresse, alors une notification unique affiche le pourcentage chez l'expéditeur et chez le receveur.
  - **AC-F4-2** - Étant donné un transfert réussi, quand il finit, alors l'expéditeur voit "Envoyé à <nom>" et le receveur "Reçu de <nom>".
  - **AC-F4-3** - Étant donné un transfert en échec, quand l'erreur survient, alors la notification affiche "Échec : " suivi d'une des quatre raisons.
  - **AC-F4-4** - Étant donné la permission de notification refusée sur Android, quand un transfert a lieu, alors il aboutit et l'état est visible dans E1.
- **Vérification** : manuelle (appareils réels) ; AC-F4-3 aussi automatique sur le message produit.

### F5 - Interface verre, clair/sombre, légère

- **Objectif** : rendu "liquid glass" sobre, minimal, économe.
- **Règles** : thème = réglage système, aucune bascule dans l'app ; verre via les API système (Windows 11 : Mica/acrylique ; Android 12+ : flou de fenêtre) ; fond opaque quand indisponible ; animations courtes, désactivées si l'utilisateur les réduit.
- **Critères** :
  - **AC-F5-1** - Étant donné le thème système changé de clair à sombre, quand l'app est ouverte, alors elle change de thème sans redémarrage.
  - **AC-F5-2** - Étant donné E1, alors il ne contient aucun bouton autre que les tuiles d'appareils et un seul bouton de sélection (Android ; sur Windows aucun), à l'exception des boutons Accepter et Refuser de la carte de demande, affichés seulement si les notifications sont refusées (AC-F4-4).
  - **AC-F5-3** - Étant donné un appareil sans flou disponible (Windows 10, Android avant 12, économie d'énergie), quand E1 s'affiche, alors le fond est opaque et le contraste du texte atteint au moins 4,5:1.
  - **AC-F5-4** - Étant donné l'app Windows au repos et fenêtre fermée dans la zone de notification, alors la mémoire reste sous 100 Mo et le processeur sous 1 % en moyenne sur 60 s (cibles H).
  - **AC-F5-5** - Étant donné l'APK release, alors sa taille ne dépasse pas 15 Mo (cible H).
  - **AC-F5-6** - Étant donné Narrator (Windows) ou TalkBack (Android), quand E1 est parcouru, alors chaque tuile et chaque bouton a un libellé et sur Windows tout est atteignable au clavier avec focus visible.
- **Vérification** : AC-F5-1, 2, 6 manuelle ; AC-F5-3 manuelle + test de contraste calculé ; AC-F5-4, 5 mesure.

## 5. Permissions et données sensibles

| Permission ou donnée | Pourquoi | Alternative plus faible | Si refusée |
|---|---|---|---|
| Android : INTERNET, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE [À vérifier] | Réseau local, multicast | Aucune | App inutilisable |
| Android : POST_NOTIFICATIONS (Android 13+) [À vérifier] | F4 | Statut dans E1 seulement | AC-F4-4 |
| Android : FOREGROUND_SERVICE et FOREGROUND_SERVICE_DATA_SYNC (service de premier plan de type dataSync pendant un transfert) [À vérifier dans la documentation Android] | Transfert long avec écran éteint | Aucun | Transfert interrompu en arrière-plan |
| Android : aucun accès large au stockage | Lecture via sélecteur et partage, écriture via MediaStore Téléchargements [À vérifier] | Sans objet | Sans objet |
| Windows : règle de pare-feu entrante pour l'app | Recevoir | Aucune | Réception impossible, message d'aide |
| Données : nom de l'appareil, empreinte de certificat, contenu des fichiers | Identifier et transférer | Sans objet | Aucune donnée n'est envoyée hors du réseau local, aucune télémétrie |

## 6. Accessibilité

Navigation clavier complète sur Windows, focus visible, libellés TalkBack/Narrator, contraste 4,5:1 minimum, état jamais porté par la couleur seule (icône + texte), notifications lisibles par lecteur d'écran, respect de la réduction des animations.

## 7. Version 2

| ID | Fonctionnalité | Condition |
|---|---|---|
| F6 | Appareils de confiance | MVP validé, besoin d'automatiser |
| F7 | Réglages minimaux | Demande réelle |
| F8 | Réception Android permanente (tuile "visible") | Réceptions manquées en usage |
| F9 | Reprise après coupure | Échecs fréquents sur gros fichiers |

## 8. Hors périmètre

| Fonctionnalité | Raison |
|---|---|
| Comptes, cloud, relais Internet | Contredit local et simple |
| iOS, macOS, Linux | Hors demande |
| Historique détaillé, statistiques | Pas de bénéfice démontré |
