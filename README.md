# IKAR Assistant

Assistant Android intelligent avec OCR local, mémoire chiffrée, outils de sécurité et modes connecté / hors connexion.

## Recherche Internet (v0.51.0)
La recherche explicite interroge trois sources publiques sans clé API :
- Wikipédia en français pour des résumés encyclopédiques ;
- Google News RSS pour des articles d'actualité ;
- OpenAlex pour des références scientifiques.

Chaque source est isolée : une panne ou une limitation d'un fournisseur n'empêche pas d'afficher les résultats des autres. Les réponses indiquent les liens et les limites de couverture. Cette fonction n'est pas un moteur exhaustif de tout le Web et ne remplace pas la vérification des sources.

## Confidentialité
- Les notes, textes OCR et documents importés restent dans la base locale chiffrée.
- Le partage de mémoire avec le modèle en ligne reste désactivé par défaut et nécessite une activation explicite.
- Les recherches Web envoient le sujet demandé aux fournisseurs correspondants ; ne recherche pas de données privées ou sensibles.

## Compatibilité et limites
- Android minSdk 23, targetSdk 34.
- La compilation vérifie les tests unitaires et la compatibilité native ARM 32 bits.
- Les capacités d'analyse de sécurité sont limitées aux fichiers, applications et dossiers auxquels Android accorde explicitement l'accès.
- Le mode hors connexion ne prétend pas être un grand modèle conversationnel général.

## Diagnostic du serveur (v0.53.0)
Dans le mode connecté, IKAR interroge la route HTTPS `/health` de l'API IKAR hébergée sur Vercel. Le diagnostic indique si le serveur répond et si les variables de configuration attendues sont présentes. Il ne lance pas une génération Hugging Face et ne garantit donc pas la disponibilité, le quota ou les crédits du fournisseur.


## Test réel de génération IA (v0.54.0)
Dans le mode connecté, le bouton « Tester une vraie réponse IA » envoie volontairement une courte demande au modèle via l'API IKAR hébergée sur Vercel et Hugging Face. Une réponse reçue confirme le fonctionnement de la chaîne de génération à cet instant. Ce test consomme une requête du fournisseur et peut échouer si le modèle, le quota, les crédits ou le réseau sont indisponibles. Le jeton d’accès est utilisé en mémoire pendant la session et n’est pas enregistré par ce test.


## Version 0.55.0 — tableau de bord des modules

Cette version ajoute une vue d'ensemble dans l'accueil IKAR pour présenter les six domaines :
- Voix : dictée Android et lecture vocale des réponses (capacités de base).
- Recherche : sources actuellement intégrées (Wikipédia, Google News et OpenAlex).
- Sécurité : contrôles locaux existants, sans prétendre remplacer un antivirus complet.
- Productivité : notes et documents locaux ; rappels avancés encore à développer.
- Interface : tableau de bord des modules.
- Fiabilité : diagnostics de l'appareil et tests automatisés existants.

Cette vue décrit honnêtement l'état des fonctions. Elle ne signifie pas que les évolutions encore indiquées comme partielles sont terminées. La compatibilité cible reste Android 6+ (minSdk 23), Android 14 ciblé (targetSdk 34) et Java/Kotlin 17.


## Version 0.56.0 — tâches et rappels locaux

- Ajout d'une liste de tâches stockée dans la mémoire locale chiffrée Room.
- Une tâche peut être marquée terminée ou supprimée.
- Un rappel peut être programmé après un délai choisi en minutes (1 minute à 30 jours).
- Les notifications sont exécutées par WorkManager ; Android peut les différer selon Doze, les restrictions batterie et les autorisations.
- Les rappels ne sont pas une alarme à l'heure exacte. L'autorisation de notifications Android 13+ doit être accordée pour afficher les notifications.


## Version 0.67.0 — refonte de l'accueil

- Refonte visuelle de l'en-tête, du panneau d'accueil et du tableau de bord en thème sombre vert.
- Présentation plus lisible des espaces Assistant IA, Recherche, Sécurité, Productivité, Protection Web et Appareil.
- Les modules existants sont conservés ; les libellés de disponibilité ne doivent pas être interprétés comme une garantie de protection complète.
- Le workflow Android s'exécute également sur les pull requests vers `main` afin de vérifier les changements avant intégration.


## Version 0.70.0 — moteur natif et règles YARA Forge

- Passage à la bibliothèque officielle libyara 4.5.0 via JNI pour ARMv7.
- Ajout du téléchargement, de la validation et du stockage hors ligne du paquet YARA Forge Core.
- Ajout de la mise à jour automatique quotidienne et du bouton d'actualisation manuelle.
- Le contrôle CI compile le paquet de règles avec la même version YARA et la configuration sans crypto utilisée par le build Android.
- Le test d'exécution sur le téléphone physique reste une étape distincte après livraison de l'APK.

## Antivirus local — libyara et YARA Forge (0.69.0)

- Le moteur officiel libyara 4.5.0 est compilé pour Android ARMv7 via NDK/JNI et embarqué dans l'APK sous `lib/armeabi-v7a/libikar_yara.so`. Le workflow contrôle explicitement sa présence et son architecture ELF.
- Le scanner natif charge le dossier de règles local et met en cache les règles compilées jusqu'à la prochaine génération de base. La limite d'analyse YARA de 16 MiB et le délai natif de 20 secondes sont conservés pour les appareils ARM32.
- IKAR utilise le paquet **Core de YARA Forge**. L'application vérifie le digest SHA-256 et la taille de l'archive, extrait les règles dans un dossier temporaire, puis les compile avec libyara avant activation.
- Une mise à jour est planifiée au démarrage et toutes les 24 heures si le réseau est disponible. Un bouton permet également de demander une actualisation manuelle depuis le module Antivirus local.
- La dernière base validée reste stockée dans le répertoire privé de l'application et fonctionne hors connexion. Une mise à jour invalide ne doit pas remplacer la base active.
- Le moteur Kotlin limité reste un secours explicite si la bibliothèque native ne se charge pas ; il ne doit jamais être présenté comme libyara officiel.
- Les règles YARA Forge conservent les licences et attributions de leurs auteurs amont. YARA détecte des motifs définis par les règles et ne garantit pas la détection de tous les malwares.
- L'analyse de fichiers conserve SHA-256, URLhaus, quarantaine AES-GCM et historique. ClamAV natif n'est pas embarqué et aucune analyse ClamAV n'est simulée.

Les résultats « aucun indicateur détecté » ne constituent pas une garantie d'innocuité. Android sans root ne permet pas à IKAR d'inspecter toutes les données privées des autres applications. Le détail du flux de mise à jour est dans [docs/YARA_FORGE_RULES.md](docs/YARA_FORGE_RULES.md).

## Version 0.69.0 — mémorisation facultative du jeton

- Le jeton d'accès API IKAR peut rester non mémorisé (comportement par défaut) ou être conservé sur demande.
- Si l'utilisateur active cette option, le jeton est chiffré en AES-GCM avec une clé conservée dans Android Keystore.
- Désactiver l'option efface la valeur chiffrée et la clé locale.
- Le jeton n'est jamais écrit en clair dans les préférences, les journaux ou l'APK. Le transport vers le backend reste en HTTPS.


## État de l'intégration native Android

- libyara 4.5.0 ARMv7 est intégré au pipeline de compilation APK. La validation finale doit encore inclure la compilation des paquets YARA Forge et un essai d'exécution sur le téléphone physique.
- Le paquet YARA Forge Core est la source principale de règles actualisables ; les règles restent disponibles localement après téléchargement.
- ClamAV reste indisponible dans l'application. Son portage Android, ses dépendances, le pont JNI, les bases de signatures et les essais ARMv7 restent des travaux séparés.
- Les résultats de scan doivent continuer à afficher séparément SHA-256, libyara/YARA Forge, réputation URLhaus et disponibilité ClamAV.

## Évaluation des moteurs antivirus alternatifs (23 novembre 2026)

Le candidat open source le plus proche d'un moteur combinant les rôles de YARA et ClamAV est **HydraDragonAV Mobile** : son code documente un moteur Rust JNI qui associe YARA-X, des signatures compatibles ClamAV, l'extraction d'archives et l'analyse statique Android. Toutefois, son dépôt indique que le projet mobile est en pause, sa licence est GPL-2.0-or-later, et la prise en charge ARMv7/armeabi-v7a n'est pas encore validée pour IKAR. Il exige aussi Android API 26 minimum. Le dépôt contient bien un fichier natif `app/src/main/jniLibs/armeabi-v7a/libhydradragonandroid.so`, ce qui en fait un candidat plus concret pour le téléphone ARM32 que les autres projets examinés. Cependant, le workflow Android ne fait qu'assembler l'APK et le workflow Rust installe la cible armv7 sans effectuer de cross-build ni de test d'exécution ARMv7. La présence du fichier ne prouve donc pas qu'il fonctionne sur l'Infinix. Il ne sera pas copié dans IKAR avant audit de licence, audit de sécurité, vérification des dépendances et compilation/test réels sur ARMv7.

Autres candidats examinés :
- **Avira MAVAPI** : SDK antivirus Android commercial documenté pour 32 et 64 bits, avec JNI/Kotlin/Java et analyse de fichiers. C'est l'alternative fournisseur la plus proche d'un moteur antivirus complet, mais elle dépend d'une licence et d'un accès SDK.
- **Hypatia** : moteur Android hors ligne fondé sur des signatures de type ClamAV, mais le dépôt amont est archivé et sous AGPL-3.0 ; ce n'est pas un remplacement direct de YARA.
- **anti-android-virus (aav)** : moteur C++17 Android orienté analyse statique DEX/APK, sous MIT, mais son dépôt se décrit comme recherche/éducation et sa base fournie ne contient que des signatures synthétiques ; il ne remplace pas un moteur antivirus généraliste.

Décision : ne pas remplacer aveuglément YARA/ClamAV par une bibliothèque non vérifiée. Priorité à un prototype isolé HydraDragonAV Mobile sur ARMv7, sans toucher au moteur actuel ; si ce candidat échoue à la compilation, à l'audit ou aux tests, garder une architecture IKAR modulaire et comparer une intégration sous licence Avira MAVAPI. Toute solution de repli doit disposer de vraies signatures actualisables hors ligne, d'une analyse de fichiers/APK, de tests EICAR et de fichiers de test bénins, et d'un rapport séparant détection, suspicion et absence de moteur.


## Catalogue des moteurs antivirus

Le comparatif détaillé des moteurs envisageables, de leurs licences, de leurs limites ARM32 Android et du plan d'intégration est maintenu dans [docs/ANTIVIRUS_ENGINE_CATALOG.md](docs/ANTIVIRUS_ENGINE_CATALOG.md). Le principe reste de ne déclarer un moteur actif qu'après compilation pour l'ABI cible et test réel d'initialisation et de détection sur appareil.
