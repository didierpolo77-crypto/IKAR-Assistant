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


## Antivirus local — étape de développement (0.68.0 en préparation)

- L'analyse de fichiers conserve le calcul SHA-256 et la comparaison avec les empreintes URLhaus déjà téléchargées.
- Un évaluateur local de règles YARA compatibles est ajouté pour un sous-ensemble documenté : chaînes ASCII littérales et conditions « any of them », « all of them » ou « N of them ». Les règles de démarrage sont heuristiques et peuvent produire des faux positifs. Ce composant n'est pas la bibliothèque officielle libyara.
- Les fichiers de 16 Mo ou moins sont évalués par ces règles ; les fichiers plus volumineux sont analysés par les autres contrôles disponibles, sans chargement intégral pour éviter une forte consommation mémoire.
- La quarantaine crée une copie chiffrée AES-GCM dans le stockage privé d'IKAR. Le fichier source n'est jamais supprimé automatiquement. La restauration crée un nouveau fichier dans un dossier choisi par l'utilisateur.
- L'historique local conserve jusqu'à 200 métadonnées d'analyse (nom, SHA-256, verdict, moteurs déclarés et date), sans contenu de fichier.
- La base URLhaus existante peut actualiser les URLs actives et les empreintes SHA-256 avec une Auth-Key fournie par l'utilisateur. Cette base ne représente pas toutes les familles de malwares.
- ClamAV n'est pas encore embarqué dans l'APK. L'application affiche explicitement son indisponibilité et n'exécute aucune fausse analyse ClamAV. L'intégration native ARMv7, ses dépendances, son pont JNI et les bases de signatures doivent être construits et testés séparément avant activation.

Les résultats « aucun indicateur détecté » ne constituent pas une garantie d'innocuité. Les analyses restent limitées aux fichiers que l'utilisateur peut explicitement ouvrir via le sélecteur Android.
