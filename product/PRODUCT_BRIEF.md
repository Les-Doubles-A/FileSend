# FileSend - Product Brief

> Version 1 · 2026-11-08 · Statut : brouillon à valider
> Légende : **[Fait]** vérifié (source ou "exprimé par l'utilisateur") · **[H#]** hypothèse · **[Reco]** recommandation · **[À vérifier]**

## 1. Concept

Application Windows et Android qui envoie fichiers et dossiers entre appareils du même réseau local, à la manière d'AirDrop : on dépose, on choisit l'appareil, l'autre accepte depuis une notification. Interface minimale, effet verre dépoli ("liquid glass"), thèmes clair et sombre, très léger.

## 2. Problème

**[Fait]** (exprimé par l'utilisateur) : envoyer des fichiers ou dossiers entre PC Windows et téléphone Android sans câble ni compte doit être aussi simple qu'AirDrop, sans interface chargée. Les contournements habituels (câble USB, messagerie, cloud) sont lents, passent par Internet ou exigent des manipulations.

## 3. Utilisateurs

- **Utilisateur principal** : l'auteur, entre son PC Windows et son téléphone Android **[H2]**.
- **Utilisateurs secondaires** : proches sur le même Wi-Fi (installation de l'app des deux côtés).
- **Hors cible** : transferts via Internet, entreprises (administration, audit), iOS/macOS/Linux (MVP).

## 4. Alternatives actuelles

| Alternative | Ce qu'elle fait bien | Limite pour notre cible | Statut |
|---|---|---|---|
| Câble USB / messagerie / cloud | Rien à installer | Lent, manuel, passe par Internet ou un compte | [Fait] connu, non sourcé |
| Quick Share pour Windows (Google) | Officiel, Android vers Windows, Wi-Fi + Bluetooth requis, 64 bits Win 10+ | Dépend de Bluetooth et du Wi-Fi activés, interface et réglages de Google, non personnalisable | Vérifié le 2026-11-08 (android.com/windows/better-together/quick-share-app) |
| LocalSend | Open source, multiplateforme, protocole REST documenté, découverte multicast UDP, HTTPS à certificat auto-signé | Interface plus riche (onglets, réglages), pas notre design ni notre budget de ressources | Vérifié le 2026-11-08 (github.com/localsend/protocol) |

**Conséquence honnête** : le besoin est déjà couvert par des outils gratuits. La valeur de FileSend est l'interface réduite à l'essentiel, le rendu verre et la légèreté, pas la fonction.

## 5. Proposition de valeur

Envoyer sans réfléchir : une seule surface, pas de réglages, des notifications comme interface principale.
- Aucun compte, aucun serveur, aucun Internet.
- Un seul écran : appareils proches + zone de dépôt.
- Thème système, verre dépoli, budget de ressources mesuré.

## 6. Hypothèses

| ID | Hypothèse | Type | Si elle est fausse | Comment la tester | Coût |
|---|---|---|---|---|---|
| H1 | Usage sur un même réseau local (Wi-Fi ou point d'accès du téléphone), jamais via Internet | Usage | Il faudrait un relais serveur | Hors périmètre, décision produit | nul |
| H2 | Usage personnel puis petit cercle ; distribution par installeur et APK, pas de store au départ | Usage | Examen store, politique de confidentialité | Décider avant publication | faible |
| H3 | Android reçoit uniquement quand l'app est ouverte ou en transfert (pas de service permanent) | Technique | Réception manquée par l'utilisateur | Test d'usage 2 semaines ; V2 : tuile "visible" | moyen |
| H4 | Verre dépoli réel sur Android 12+ et Windows 11 ; fond opaque ailleurs | Technique | Rendu dégradé sur anciens appareils | Test sur appareils réels | faible |
| H5 | Un consentement par transfert (notification Accepter/Refuser) suffit, sans appairage ni PIN | Usage | Spam ou réception non voulue sur Wi-Fi partagé | Test en réseau partagé ; V2 : appareils de confiance | faible |
| H6 | Le multicast et le trafic client à client sont autorisés sur le réseau de l'utilisateur | Technique | Découverte impossible sur ce réseau | Test sur 3 réseaux (domicile, point d'accès, public) ; repli : scan de sous-réseau | faible |
| H7 | Invalidée le 2026-11-08 : la spécification LocalSend (v2.2) n'a pas de champ de chemin et n'indique pas de licence ; protocole propre adopté (D1) | Technique | Sans objet | Sans objet | Sans objet |
| H8 | Tauri 2 offre Mica/acrylique, zone de notification et démarrage automatique avec un faible poids **[À vérifier]** | Technique | Repli WinUI 3 (Mica et acrylique documentés par Microsoft) | Spike D2 | faible |
| H9 | Textes en français seul au MVP, très courts | Usage | Ajout d'une couche i18n | n/a | faible |

## 7. Tester l'intérêt avant d'investir

Spike de 1 à 2 jours : transfert d'un fichier Windows vers Android sur le réseau domestique (tranche 1 + T6/T7 minimal). Usage réel pendant deux semaines. **Arrêter ou pivoter** si le rendu des appareils ne fonctionne pas sur le réseau de l'utilisateur (H6) ou si LocalSend remplit déjà le besoin sans friction pour l'auteur : dans ce cas l'intérêt est le design seul, à assumer comme projet d'apprentissage.

## 8. Objectifs et indicateurs de réussite

| Objectif | Indicateur | Seuil MVP | Mesure |
|---|---|---|---|
| Simplicité | Taps/clics pour envoyer un fichier à un appareil connu | 3 au maximum (sélection, appareil, [acceptation côté receveur]) | Manuelle |
| Fiabilité | Transferts réussis sur 20 essais (fichiers et dossiers) | 20 sur 20 | Manuelle |
| Légèreté Windows | Mémoire au repos ; taille de l'installeur | ≤ 100 Mo ; ≤ 15 Mo (cibles H, à mesurer) | Gestionnaire des tâches |
| Légèreté Android | Taille APK release | ≤ 15 Mo (cible H) | Build |

## 9. Périmètre du MVP

- **MVP** : F1 Découverte, F2 Envoi fichiers/dossiers, F3 Réception avec consentement, F4 Notifications d'état, F5 Interface verre claire/sombre.
- **Version 2** : appareils de confiance (acceptation automatique), réglages (nom, dossier), service de réception Android permanent, reprise après coupure, texte/presse-papier, anglais.
- **Hors périmètre** : comptes, cloud, relais Internet, synchronisation, iOS/macOS/Linux, historique détaillé.

## 10. Contraintes

**[Fait]** (exprimé par l'utilisateur) : Windows + Android ; local ; fonctionnement type AirDrop ; peu de boutons, surtout des notifications (envoi, envoyé, reçu) ; UI/UX très simple ; liquid glass si possible ; pas verbeux ; mode sombre et clair ; le plus léger et optimisé possible. Pas de contrainte de budget exprimée : coût visé nul (aucune dépendance payante).

## 11. Questions ouvertes

- D1, D2, D3 du plan d'implémentation (protocole, framework Windows, nom de l'application et identifiant).
- Android doit-il recevoir en arrière-plan dès le MVP (H3) ?

## Sources

- Quick Share pour Windows : https://android.com/windows/better-together/quick-share-app (consulté le 2026-11-08, via recherche)
- Protocole LocalSend : https://github.com/localsend/protocol (consulté le 2026-11-08, via résultats de recherche ; spécification non lue en entier)
- Flou de fenêtre Android (API 31) : https://source.android.com/docs/core/display/window-blurs (consulté le 2026-11-08)
- Matériaux Windows (Mica, acrylique) : https://learn.microsoft.com/en-us/windows/apps/develop/ui/system-backdrops (consulté le 2026-11-08)
