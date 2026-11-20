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
Dans le mode connecté, IKAR peut interroger la route HTTPS `/health` de Render. Le diagnostic indique si le serveur répond et si les variables de configuration attendues sont présentes. Il ne lance pas une génération Hugging Face et ne garantit donc pas la disponibilité, le quota ou les crédits du fournisseur.
