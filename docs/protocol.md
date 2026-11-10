# Protocole FileSend v1

Décision D1 (2026-11-08) : protocole propre, de forme voisine de celui de LocalSend (spécification publique consultée en v2.2 : REST, découverte multicast, jeton par fichier). Raisons : la spécification ne définit aucun champ de chemin, donc pas de dossiers, et aucune licence n'est indiquée dans ce qui a été lu. L'interopérabilité avec LocalSend n'est pas un objectif. Le port par défaut diffère (53318) pour éviter toute collision.

Tout passe en HTTPS (TLS, certificat auto-signé généré par appareil). Un appareil n'écoute jamais en HTTP clair. Les noms de champs JSON sont en camelCase.

## Constantes

| Nom | Valeur |
|---|---|
| Port par défaut | 53318 (TCP et UDP) |
| Groupe multicast (T4) | 224.0.0.167 |
| Délai de réponse du receveur | 60 s |
| Inactivité maximale d'un transfert | 10 s |
| Préfixe des routes | `/api/filesend/v1` |

## Objet `info`

`{ "alias": "Mon PC", "fingerprint": "<sha256 hex du certificat>", "deviceType": "desktop" | "mobile" }`

## POST /prepare-upload

Requête :
```json
{ "info": {...},
  "files": { "<id>": { "id": "<id>", "relativePath": "photos/v/b.jpg", "size": 1234, "sha256": "<hex, optionnel>" } } }
```
`relativePath` utilise `/` ; un fichier seul n'a pas de dossier. Le serveur répond après la décision de l'utilisateur (au plus 60 s ; le client doit patienter au moins 70 s).

Réponses :
- `200` : `{ "sessionId": "<uuid>", "files": { "<id>": "<jeton>" } }`
- `403` : refus ou délai écoulé
- `400` : requête invalide (chemin interdit, doublon, trop de fichiers)
- `409` : une autre séance est en cours

## POST /upload?sessionId=&fileId=&token=

Corps : octets du fichier (flux). Le serveur écrit dans un fichier `.part` puis renomme à la fin.
- `200` : fichier reçu
- `403` : jeton ou séance invalide
- `422` : taille ou sha256 différents de la déclaration
- `507` : disque plein
- `500` : autre erreur d'écriture

Un fichier ne peut être envoyé qu'une fois. La séance se termine quand tous les fichiers sont reçus.

## POST /cancel?sessionId=

Annule la séance (par l'un ou l'autre) : les fichiers partiels sont supprimés. `200`.

## Règles du receveur

- Aucun octet écrit avant accord.
- Chemins : refus (`400`) de `..`, `.`, chemins absolus ou commençant par un séparateur, `\`, `:`, caractères `<>"|?*` et de contrôle, noms réservés Windows (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`, avec ou sans extension), composants finissant par un point ou une espace, composant > 255 caractères.
- Collision : le premier composant du chemin reçoit un suffixe ` (n)` s'il existe déjà dans le dossier de réception (`a.txt` devient `a (1).txt`, `photos` devient `photos (1)`). Jamais d'écrasement.
- Une séance à la fois ; séance inactive depuis plus de 120 s remplaçable.

## Exigences HTTP

Content-Length est obligatoire pour tout corps ; Transfer-Encoding est refusé (411). Le receveur Kotlin gère les connexions persistantes et ferme celles dont le corps n'a pas été lu en entier. En-têtes limités à 32 Ko, 100 en-têtes, 32 connexions simultanées.

## GET /info

Retourne l'objet `info` de l'appareil (`200`). Sert au sondage de repli.

## Découverte

**Annonces (multicast UDP)** : paquet JSON `{ "info": {...}, "port": 53318, "announce": true }` envoyé à `224.0.0.167:<port UDP>` à la mise en route puis toutes les 3 s. Le receveur prend l'adresse IP dans le paquet reçu, jamais dans son contenu. Sont ignorés : paquets > 2048 octets, JSON invalide, `announce` faux, `port` nul, alias vide après nettoyage, sa propre empreinte. L'alias est nettoyé (caractères de contrôle retirés, 32 caractères au plus). 256 appareils au plus.

**Expiration** : un appareil muet depuis plus de 10 s (TTL) est retiré, donc moins de 15 s après sa fermeture.

**Repli** : si aucun appareil n'est connu 1,5 s après le démarrage, puis toutes les 10 s tant que la liste reste vide, l'appareil sonde les 254 adresses du /24 de son interface principale (connexion TCP de 300 ms, puis `GET /info`). Les appareils trouvés ainsi sont reverifiés toutes les 4 s par `GET /info` et retirés s'ils ne répondent plus. Limite : seuls les réseaux /24 sont sondés.