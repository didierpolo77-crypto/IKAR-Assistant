# Catalogue des moteurs d'analyse antivirus pour IKAR

Date d'évaluation : 23 novembre 2026.

## Objectif et contrainte matérielle

IKAR doit pouvoir analyser localement, sans réseau, les fichiers auxquels Android autorise l'accès. Le téléphone de référence est un appareil Android 14 en ARM32 (ABI `armeabi-v7a`). Un moteur qui ne compile qu'en ARM64 n'est donc pas une solution utilisable pour ce téléphone. Toute intégration doit être compilée pour ARMv7, incluse dans l'APK, puis exécutée sur un appareil réel avant d'être déclarée disponible.

## Liste des moteurs et composants envisageables

| Moteur / projet | Rôle | Hors ligne | ARM32 Android | Licence / accès | Décision IKAR |
|---|---|---:|---|---|---|
| **YARA officiel (libyara)** | Détection par règles de motifs, chaînes et conditions | Oui | Compilation NDK/JNI à réaliser et tester | BSD-3-Clause | Candidat prioritaire comme moteur de règles généraliste |
| **YARA-X** | Nouvelle implémentation YARA en Rust, règles YARA-X | Oui | Cross-compilation Android ARMv7 à prouver | BSD-3-Clause | Candidat à comparer à libyara ; ne pas supposer une compatibilité totale des règles |
| **ClamAV libclamav** | Antivirus par signatures, formats et archives | Oui après téléchargement des bases | Portage Android et dépendances à compiler ; non validé | GPL-2.0 | Candidat ambitieux, pas activable tant que le portage ARMv7 n'est pas prouvé |
| **HydraDragonAV Mobile** | Scanner Android combinant YARA-X et signatures compatibles ClamAV, extraction d'archives et analyse statique | Oui pour les règles embarquées | Dépôt contenant un binaire armeabi-v7a, mais compilation et exécution ARMv7 non validées | GPL-2.0-or-later ; projet amont annoncé en pause | Prototype isolé seulement ; audit de licence, dépendances et sécurité requis |
| **Hypatia** | Scanner Android basé sur des bases de signatures de style ClamAV | Oui, bases actualisables | Ancien projet Android ; maintenance et intégration actuelles à vérifier | AGPL-3.0 ; dépôt amont archivé selon l'évaluation précédente | Référence de conception, pas moteur à intégrer directement |
| **Avira MAVAPI** | SDK antivirus commercial pour fichiers Android | Selon SDK et bases fournies | Documentation fournisseur mentionne Android 32/64 bits | Commercial, accès et licence fournisseur nécessaires | Meilleur plan fournisseur si budget et contrat disponibles |
| **anti-android-virus (aav)** | Analyse statique C++ des APK/DEX : classes, opcodes et signatures | Oui | Le dépôt documente Android, mais CI Android uniquement ARM64 ; ARMv7 à vérifier | MIT | Bon composant de recherche APK/DEX, pas antivirus généraliste ; base fournie synthétique |
| **Androguard** | Parsing, désassemblage et inspection APK/DEX, permissions, chaînes et composants | Oui si embarqué | Outil Python, coût de portage et empreinte à mesurer | Apache-2.0 | Outil d'analyse complémentaire, pas moteur de signatures antivirus |
| **APKiD** | Identification de packers, obfuscateurs et traces de compilateurs dans les APK | Oui avec règles locales | Outil Python ; intégration embarquée à évaluer | GPL-3.0 | Analyse complémentaire de risque APK, pas antivirus complet |
| **Moteur IKAR modulaire** | SHA-256, règles locales, analyse APK/DEX, heuristiques, signatures propres et réputation | Oui pour les composants locaux | Contrôle total du build ARM32 | Licence du projet à clarifier avant ajout de dépendances copyleft | Solution de repli maîtrisable, à développer et tester par couches |

## Sources publiques consultées

- HydraDragonAV Mobile : https://github.com/HydraDragonAntivirus/HydraDragonAV-Mobile
- anti-android-virus : https://github.com/yanghuafang/anti-android-virus
- Hypatia : https://github.com/Divested-Mobile/Hypatia
- Androguard : https://github.com/androguard/androguard
- APKiD : https://github.com/rednaga/APKiD
- ClamAV : https://github.com/Cisco-Talos/clamav
- YARA : https://github.com/VirusTotal/yara
- YARA-X : https://github.com/VirusTotal/yara-x
- Avira MAVAPI : https://www.avira.com/en/partners/oem

## Choix technique recommandé

Ne pas chercher un seul paquet qui prétend tout faire. Construire une chaîne à moteurs indépendants :

1. **SHA-256 + base locale de hachages** : identification exacte de fichiers connus, sans conclure qu'un fichier inconnu est sain.
2. **YARA officiel** : règles de détection locales actualisables, avec tests de compilation et d'exécution ARMv7.
3. **Analyse APK/DEX** : manifeste, permissions, certificat, composants, chaînes et indicateurs statiques. Androguard/APKiD sont des références possibles, mais leur coût d'intégration Android doit être mesuré.
4. **Signatures antivirus** : intégrer ClamAV uniquement après portage ARMv7 reproductible, ou évaluer HydraDragon dans un prototype juridiquement compatible. Les bases doivent être versionnées, authentifiées et actualisables.
5. **Heuristiques comportementales statiques** : signaler des combinaisons d'indicateurs sans les présenter comme preuve d'infection.
6. **Réputation en ligne facultative** : URLhaus et services distants comme couche complémentaire, jamais requise pour le scan hors connexion.

## Plan d'intégration et critères d'acceptation

- Étape A : garder le scanner actuel fonctionnel et créer une interface commune de moteurs avec états explicites : disponible, indisponible, erreur, non exécuté.
- Étape B : compiler libyara pour `armeabi-v7a` avec Android NDK et JNI ; ajouter des tests de règles positives et négatives.
- Étape C : étudier le build ARMv7 de ClamAV et HydraDragon sans copier de code sous GPL dans IKAR avant décision de licence.
- Étape D : renforcer l'analyse APK/DEX et les signatures locales actualisables.
- Étape E : tester sur l'appareil cible, avec échantillons de test inoffensifs, faux positifs, fichiers volumineux, archives, fichiers corrompus et mode avion.

Un moteur ne sera affiché « actif » que si sa bibliothèque native est présente pour l'ABI, son initialisation réussit et un test de détection contrôlé réussit. Un moteur absent ou en échec doit apparaître comme indisponible, jamais comme « aucun virus détecté ».

## Limites à conserver dans l'interface

- Une empreinte SHA-256 identifie un fichier ; elle ne détecte pas à elle seule un malware inconnu.
- Une règle déclenchée indique une correspondance, pas automatiquement une infection certaine.
- « Aucun indicateur détecté » signifie seulement qu'aucun des contrôles exécutés n'a trouvé d'indicateur.
- Android sans root ne permet pas à IKAR d'inspecter librement les données privées des autres applications ni de garantir une surveillance totale du système.
- La quarantaine doit rester réversible et ne jamais supprimer automatiquement le fichier source.
