# FileSend

Dépôt GitHub : **https://github.com/Les-Doubles-A/FileSend**

Envoi de fichiers et dossiers en réseau local, Windows et Android. Plan complet : `product/`. Protocole : `docs/protocol.md`.

État : cœur Windows (tranche 1), découverte (T4), application Windows (T5, vérifications manuelles restantes), cœur Android (T6). Suite : voir `product/IMPLEMENTATION_PLAN.md`.

## Cœur Windows (`windows/core`)

Prérequis : Rust (testé avec cargo 1.98.1).

```
cd windows/core
cargo test                                  # tests unitaires et d'intégration
cargo test -- --ignored big_file_2gb        # test lourd (2 Go)
cargo build --release                       # produit target/release/filesend-dev.exe
```

## Application Windows (`windows/app`)

```
cd windows
cargo build --release -p filesend-app       # windows/target/release/filesend-app.exe
```

Options : `--minimized` (démarre dans la zone de notification), `--remove-sendto` (retire "Envoyer vers"). Variable `FILESEND_NO_SENDTO=1` : n'ajoute pas l'entrée "Envoyer vers" au premier lancement d'un build release. Reçus dans `Téléchargements\FileSend`. Contraste des couleurs : `node tools/check-contrast.mjs`.

Installeur (Inno Setup 6 requis) : `powershell -File windows\installer\build.ps1` produit `windows\installer\Output\FileSend-Setup-0.1.0.exe`. Installation par utilisateur (sans droits admin), raccourci menu Démarrer, entrée "Envoyer vers", démarrage automatique optionnel. La désinstallation retire "Envoyer vers".

## Android (`android`)

Prérequis : JDK 17 (`JAVA_HOME`), SDK Android (`ANDROID_HOME`, plateforme 36). Le wrapper Gradle télécharge Gradle 8.14.3.

``
cd android
gradlew.bat test                       # tests du coeur (dont interoperabilite si windows/core est compile en release)
gradlew.bat :app:assembleRelease       # APK non signe : app/build/outputs/apk/release
``

Vecteurs communs Rust/Kotlin : `protocol-tests/vectors.json`.

Diagnostic de la découverte : `filesend-dev discover [secondes]` affiche chaque appareil vu et la voie (Multicast ou Scan). Variables : `FILESEND_LOG=<fichier>` (journal des appareils de l'application Windows), `FILESEND_MCAST_IF=default|<adresse IPv4>` (interface multicast).

Essai manuel entre deux terminaux (le receveur accepte tout) :

```
filesend-dev receive C:\temp\recu 53391
filesend-dev send 127.0.0.1:53391 C:\chemin\fichier_ou_dossier
```

Le certificat TLS est généré au premier lancement dans `<dossier>\.identity`. Ne le versionnez pas.

<!-- installeur-release -->
## Installation

Téléchargez **`FileSend-Setup-0.1.0.exe`** depuis la [dernière release](https://github.com/Les-Doubles-A/FileSend/releases/latest), puis lancez-le.

L'installation se fait pour l'utilisateur courant, sans droits administrateur (par défaut dans `%LOCALAPPDATA%\Programs\FileSend`), avec un raccourci dans le menu Démarrer et une désinstallation depuis les Paramètres Windows.

L'installeur n'est pas signé : si Windows SmartScreen l'affiche, cliquez sur « Informations complémentaires » puis « Exécuter quand même ».

La release contient aussi l'APK Android (`FileSend-0.1.0.apk`).

### Recréer l'installeur

Compiler l'application (`cargo build --release -p filesend-app (dans windows/)`), puis lancer [Inno Setup 6](https://jrsoftware.org/isinfo.php) : `iscc /DAppVersion=0.1.0 windows\installer\filesend.iss`.
