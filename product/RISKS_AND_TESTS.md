# FileSend - Risques et tests

> Version 1 · 2026-11-08

## 1. Risques

| ID | Risque | Type | Probabilité | Impact | Prévention | Repli |
|---|---|---|---|---|---|---|
| R1 | Multicast bloqué ou isolation client sur le réseau | Technique | Moyenne | Fort | Repli par sondage de sous-réseau ; message "Même Wi-Fi ?" | Saisie manuelle d'adresse (V2) |
| R2 | Pare-feu Windows bloque la réception | Plateforme | Forte au premier lancement | Moyen | Invite du pare-feu documentée, message d'aide | Règle manuelle |
| R3 | Android n'est pas joignable quand l'app est fermée ou l'écran éteint (H3) | Fonctionnel | Forte | Moyen | Le dire clairement ; service de premier plan pendant un transfert | F8 |
| R4 | Usurpation d'un appareil sur Wi-Fi partagé (certificat auto-signé) | Sécurité | Faible à moyenne | Moyen | Consentement visible avec nom ; aucune écriture avant accord | Appareils de confiance et épinglage (V2) |
| R5 | Chemin piégé écrit hors du dossier | Sécurité | Moyenne | Fort | AC-F2-4, tests dédiés, vérification finale sous la racine | Refuser la séance |
| R6 | Flou coûteux ou indisponible (économie d'énergie, anciens appareils) | Technique | Moyenne | Faible | Repli opaque (AC-F5-3), rayon modéré | Fond opaque permanent |
| R7 | Limites de plateforme Tauri/Android différentes de ce qui est supposé (H7, H8) | Technique | Moyenne | Moyen | Spikes D1 et D2 avant T1 | WinUI 3 |
| R8 | Le besoin est déjà couvert par LocalSend et Quick Share | Fonctionnel | Forte | Moyen | Assumer le projet comme design et légèreté | Utiliser l'existant |
| R9 | Cibles de poids et mémoire irréalistes (H) | Technique | Moyenne | Faible | Mesure en T5 et T9 | Réviser les seuils |
| R10 | Évolutions de politiques de plateforme (notifications, stockage Android) | Plateforme | Moyenne | Moyen | Vérifier dans la documentation officielle à T6 | Adapter |
| R11 | La notification Windows est une fenêtre de l'application : absente du centre de notifications, invisible au-dessus d'une application plein écran exclusif [À vérifier] | Fonctionnel | Moyenne | Faible | Fenêtre toujours au premier plan, utilisable au clic | Notifications système sans boutons, accord depuis la fenêtre principale (V2) |
| R12 | Lecture d'un fichier partagé impossible (permission temporaire de l'URI perdue, fournisseur qui ne donne pas la taille) | Technique | Moyenne | Fort | Liste construite au moment du toucher ; copie temporaire si la taille est inconnue ; journal technique ; échec lisible "Interrompu" | Choisir le fichier via "+" (permission durable pendant la session) |
| R13 | Le réseau local ne transmet pas le multicast (constaté sur le Wi-Fi domestique de l'utilisateur) | Technique | Forte (constatée) | Moyen | Repli par sondage du /24 démarré à 1,5 s : 2,9 s côté PC, 5 s environ côté téléphone en mesure brute | Saisie manuelle d'adresse (V2) ; réseaux au-delà d'un /24 non couverts |
| R14 | Un envoi de 1,5 Go du téléphone vers le PC a échoué une fois ("Interrompu", aucun octet reçu pendant 10 s au démarrage) ; 5 envois identiques suivants réussis ; cause non établie (coïncidait avec un sondage très rapproché de la fenêtre Windows par l'outil de test) | Technique | Faible à moyenne | Moyen | Journal technique Android (`logcat`, étiquette FileSend) ; à surveiller en usage | Réessayer ; délai d'inactivité configurable si le cas se reproduit |

## 2. Hypothèses non vérifiées

| ID | Hypothèse | Comment la vérifier | Quand | Si fausse |
|---|---|---|---|---|
| H3 | Réception Android app ouverte acceptable | Comportement technique vérifié sur appareil le 2026-11-08 (arrêt en arrière-plan, reprise au retour) ; acceptabilité : usage de deux semaines | Après T10 | F8 |
| H5 | Consentement par transfert suffit | Test sur réseau partagé | Après T10 | F6 |
| H6 | Multicast fonctionne sur les réseaux de l'utilisateur | **Infirmée sur le Wi-Fi domestique** (2026-11-08) : le multicast ne passe pas entre le PC et le téléphone, quelle que soit l'interface du PC ; le repli par sondage fonctionne. Restent à essayer : le point d'accès du téléphone et un réseau public (M4) | T7, T10 | Repli par sondage (en place), saisie d'adresse (V2) |
| H7 | Spécification LocalSend utilisable | Lecture complète et licence | D1 | Protocole propre |
| H8 | Tauri 2 convient | Spike D2 | Avant T1 | WinUI 3 |
| H4 | Verre réel sur Windows 11 et Android 12+ | Essai appareils | T5, T9 | Opaque |

## 3. Stratégie de test

| Niveau | Outil | Couvert | Quand |
|---|---|---|---|
| Unitaire | `cargo test`, JUnit | Chemins, collisions, états de séance, messages | À chaque modification |
| Intégration | `cargo test` (deux instances locales), vecteurs partagés | Protocole complet, refus, délai, annulation, découverte | À chaque modification |
| Bout en bout | Aucun outil (appareils réels) | Voir manuel | Avant livraison |
| Manuel | Scénarios M1 à M5 | Notifications, verre, accessibilité, Windows/Android réels | Avant chaque livraison |

**Commande unique** : `cargo test` dans `windows/core` ; `./gradlew test` dans `android`.

## 4. Couverture des critères d'acceptation

| Critère | Vérification | Test ou scénario | Tâche | État (T10) |
|---|---|---|---|---|
| AC-F1-1 | Automatique + manuelle | Intégration découverte ; M1 | T4, T7 | **Partiel** : ce Wi-Fi ne transmet pas le multicast (mesuré dans les deux sens) ; repli par sondage : le PC voit le téléphone en 2,9 s (4 essais), le téléphone voit le PC en 5,0 à 5,5 s en mesure brute (démarrage à froid et lecture d'écran compris) ; auto Rust et Kotlin |
| AC-F1-2 | Automatique + manuelle | Expiration ; M4 | T4, T7 | **Vérifié** : auto ; réel : PC fermé, disparu de la liste du téléphone en 13 s mesurées (lecture d'écran comprise), seuil 15 s |
| AC-F1-3 | Automatique + manuelle | Multicast coupé ; M4 | T4, T7 | **Vérifié** : auto ; réel : le multicast ne passe pas sur ce réseau, la découverte se fait par le balayage réel du /24, côté PC et côté téléphone |
| AC-F1-4 | Manuelle | M5 | T5, T7, T9 | **Vérifié** : texte exact lu sur Windows et sur Android |
| AC-F2-1 | Automatique + manuelle | Fichier 10 Mo SHA-256 ; M1, M2 | T3, T8 | **Vérifié** : auto ; réel dans les deux sens, sha256 identique ; 20 sur 20 du téléphone vers le PC ; 300 Mo et 1,5 Go du téléphone vers le PC |
| AC-F2-2 | Automatique + manuelle | Dossier ; M2 | T3, T8 | **Partiel** : auto ; réel PC vers téléphone ; 10 sur 10 dossiers Windows vers Windows ; dossier choisi sur Android non vérifié |
| AC-F2-3 | Automatique | Collision | T2, T6 | **Vérifié** : auto ; réel sur Windows et sur Android |
| AC-F2-4 | Automatique | Chemins piégés | T2, T6 | **Vérifié** : auto : 31 chemins invalides partagés Rust et Kotlin, requêtes brutes |
| AC-F2-5 | Automatique + manuelle | Coupure simulée ; M3 | T2, T3, T6 | **Vérifié** : auto ; réel : Wi-Fi du téléphone coupé en plein transfert de 1,5 Go : "Échec : Interrompu" sur le téléphone à environ 7 s et sur le PC 10,7 s après la coupure, aucun fichier partiel. Critère révisé de 10 s à 15 s (délai d'inactivité de 10 s) |
| AC-F2-6 | Mesure | Fichier 2 Go, mémoire | T3, T8 | **Vérifié** : mesure : Rust 13 et 16 Mo pour 2 Go ; Kotlin 2 Go sous 128 Mo de tas ; 1,5 Go du téléphone vers le PC réel |
| AC-F3-1 | Automatique + manuelle | Aucune écriture avant accord ; M1 | T2, T7 | **Vérifié** : auto ; réel : notification Android lue ("PC-ADAM veut envoyer 1 élément", "2,9 Mo", actions Refuser et Accepter, délai d'une minute) ; fenêtre Windows lue |
| AC-F3-2 | Automatique + manuelle | Refus ; M3 | T2, T7 | **Partiel** : auto ; réel : refus d'un envoi Android sur le PC (le téléphone affiche "Échec : Refusé", rien d'enregistré) ; bouton Refuser de la notification Android non touché |
| AC-F3-3 | Automatique | Délai 60 s simulé | T2, T7 | **Vérifié** : auto ; réel : demande sur Android laissée 60 s, le PC affiche "Échec : Refusé", notification retirée, rien d'enregistré |
| AC-F3-4 | Manuelle | M1 | T3, T5, T7 | **Partiel** : enregistrement vérifié sur Windows et Android ; ouverture au toucher de "Reçu" non vérifiée |
| AC-F3-5 | Automatique | HTTP clair rejeté | T2, T6 | **Vérifié** : auto Rust et Kotlin |
| AC-F4-1 | Manuelle | M1, M2 | T5, T8 | **Partiel** : téléphone : notification de progression lue pendant un envoi de 300 Mo et de 1,5 Go (0 à 100 %, 74 relevés) ; PC : état "Réception de ..." vu, pourcentage non lu |
| AC-F4-2 | Manuelle | M1, M2 | T5, T8 | **Vérifié** : "Envoyé à" vu sur Windows et sur Android ; "Reçu de" vu sur la fenêtre Windows et sur l'écran Android |
| AC-F4-3 | Automatique + manuelle | Message d'erreur ; M3 | T5, T8 | **Vérifié** : messages auto ; réel : "Refusé" (Windows et Android), "Introuvable" et "Interrompu" (Android, coupure réseau comprise) |
| AC-F4-4 | Manuelle | M3 avec permission refusée | T7 | **Vérifié** : appareil : demande acceptée depuis l'écran sans passer par la notification (T7) |
| AC-F5-1 | Manuelle | M5 | T5, T9 | **Vérifié** : Android sans quitter l'application ; Windows : même fenêtre et même processus, clair puis sombre à chaud (captures lues) |
| AC-F5-2 | Manuelle | M5 | T5, T9 | **Vérifié** : arbres d'accessibilité Windows (un seul bouton : la tuile) et Android |
| AC-F5-3 | Manuelle + calcul | Contraste calculé ; M5 | T5, T9 | **Partiel** : contraste calculé (181 contrôles) et politique testée ; Windows : le fond rendu a les couleurs exactes de Mica (#F3F3F3 et #202020, pas celles du repli) ; repli opaque jamais vu (non déclenchable sur le téléphone, Windows 10 non testé) |
| AC-F5-4 | Mesure | Gestionnaire des tâches 60 s | T5 | **Vérifié** : mesure : 2,6 Mo de mémoire privée, 0,01 % de processeur sur 60 s |
| AC-F5-5 | Mesure | Taille APK release | T9 | **Vérifié** : mesure : 1,83 Mo |
| AC-F5-6 | Manuelle | Narrator, TalkBack, clavier ; M5 | T5, T9 | **Non vérifié** : Narrator, TalkBack et navigation au clavier non exécutés ; noms accessibles lus dans les arbres ; focalisation clavier d'une tuile non confirmée (lecture d'accessibilité instable) |

## 5. Scénarios de test manuels

### M1 - Windows vers Android, fichier
- **Préparation** : deux apps ouvertes sur le même Wi-Fi.
- **Étapes** : glisser une photo sur la tuile du téléphone ; accepter sur le téléphone.
- **Attendu** : "Reçu de <nom>" sur le téléphone, "Envoyé à <nom>" sur le PC, fichier dans Téléchargements/FileSend.
- **Critères** : AC-F1-1, AC-F2-1, AC-F3-1, AC-F3-4, AC-F4-1, AC-F4-2.

### M2 - Android vers Windows, dossier
- **Étapes** : partager un dossier depuis le téléphone vers le PC ; accepter.
- **Attendu** : arborescence identique chez le PC.
- **Critères** : AC-F2-1, AC-F2-2, AC-F4-1, AC-F4-2.

### M3 - Refus, annulation, coupure
- **Étapes** : refuser ; ne pas répondre 60 s ; couper le Wi-Fi en plein transfert ; refaire avec la permission de notification refusée sur Android.
- **Attendu** : "Échec : Refusé" ou "Interrompu", aucun fichier partiel, transferts normaux sans notification.
- **Critères** : AC-F2-5, AC-F3-2, AC-F4-3, AC-F4-4.

### M4 - Réseaux
- **Étapes** : répéter M1 sur le Wi-Fi domestique, le point d'accès du téléphone, un réseau public.
- **Attendu** : appareil visible ou message "Même Wi-Fi ?".
- **Critères** : AC-F1-1, AC-F1-2, AC-F1-3.

### M5 - Interface
- **Étapes** : basculer clair/sombre ; activer l'économie d'énergie ; naviguer au clavier, avec Narrator et TalkBack.
- **Attendu** : voir critères F5.
- **Critères** : AC-F1-4, AC-F5-1, AC-F5-2, AC-F5-3, AC-F5-6.

## 6. Validation du MVP

**Verdict (2026-11-08) : MVP non validé.** Sur 25 critères : 18 vérifiés, 6 partiellement, 1 non vérifié (tableau de la section 4). Toutes les fonctions sont construites et les parcours principaux ont tourné sur le matériel réel (PC et téléphone sur le Wi-Fi domestique) ; ce qui manque demande surtout des gestes sur le téléphone ou un lecteur d'écran.

- [ ] tous les critères d'acceptation MVP sont vérifiés : non (6 partiels, 1 non vérifié) ;
- [x] les commandes de test passent sans échec : Rust 36 tests (1 lourd ignoré par défaut), Kotlin 58 tests (1 lourd ignoré par défaut, passé avec `-PbigTests`), contraste, traçabilité ;
- [ ] M1 à M5 exécutés sur appareils réels : M1 et M2 faits dans les deux sens (acceptation du PC vers le téléphone faite à la main par l'utilisateur), M3 fait (refus, 60 s, coupure Wi-Fi), M4 sur un seul réseau (sans multicast), M5 sans lecteur d'écran ;
- [x] 20 transferts consécutifs réussis : 20 sur 20 du téléphone vers le PC (fichier de 1 Mo, contenu vérifié) et 20 sur 20 Windows vers Windows (10 dossiers) ; 300 Mo et 1,5 Go du téléphone vers le PC (sha256 identique, 4 envois de 1,5 Go réussis sur 5) ; mémoire au repos Windows 2,6 Mo ; APK 1,83 Mo. Limite : aucun dossier envoyé depuis Android.

### Reste à faire à la main

1. **Android, notification** : toucher Accepter puis, sur une autre demande, Refuser (AC-F3-2) ; toucher "Reçu de ..." : l'application Téléchargements s'ouvre (AC-F3-4) ; lire le pourcentage de la fenêtre Windows pendant un gros envoi (AC-F4-1).
2. **Android, sélecteurs** : bouton "+", Fichiers (deux fichiers) puis Dossier (avec un sous-dossier), toucher la tuile du PC : arborescence identique sur le PC (AC-F2-2).
3. **Accessibilité** : Narrator et clavier sur Windows (Tab jusqu'à la tuile, Entrée, Échap) ; TalkBack sur Android (AC-F5-6).
4. **Repli opaque** : Android 10 ou 11, ou un téléphone où l'économie d'énergie coupe le flou ; Windows 10 si disponible (AC-F5-3).
5. **Réseaux** : point d'accès du téléphone et réseau public (AC-F1-1, H6) ; mesurer le délai du téléphone qui voit le PC sans la lecture d'écran.
6. **Fiabilité** : relancer quelques envois de 1 Go et plus du téléphone vers le PC pour voir si l'échec isolé (R14) se reproduit.

Quand ces étapes sont faites, mettre à jour la colonne "État" de la section 4 et cocher les cases ci-dessus.

## 7. Après le lancement

Noter pendant deux semaines : transferts manqués côté Android (H3), acceptations répétitives (H5), réseaux où la découverte échoue (H6), temps de démarrage ressenti.
