# Instructions permanentes du projet — Tor / Arti pour Piko (Android / X)

> **Portée :** ce document décrit le contexte, la vision, les contraintes et la méthode de travail à conserver dans toutes les sessions d'agents (notamment Devin) intervenant sur ce dépôt.
>
> **Important :** il s'agit d'un **objectif de développement**, et non d'une fonctionnalité déjà implémentée ou dont la sécurité serait démontrée. Ne jamais présenter une capacité comme opérationnelle ou sans fuite réseau sans validation effective.
>
> **Priorité :** toute tâche en rapport avec Tor, Arti, le réseau ou les patchs X doit rester cohérente avec ce document. Pour les autres tâches du dépôt, ne pas imposer inutilement le périmètre Tor.

## 1. Contexte et objectif principal

- **Dépôt du fork :** https://github.com/nassim-arifette/piko
- **Projet :** Piko, projet open source de patchs Morphe pour les applications Android Twitter/X et Instagram.
- **Périmètre de la première version :** **X uniquement**, pas Instagram.
- **But :** créer un **patch Piko optionnel**, envisagé sous le nom `TorNetworkPatch`, permettant d'intégrer nativement **Tor via Arti** (implémentation de Tor en Rust) à l'application X patchée avec Morphe.

L'utilisateur doit pouvoir activer Tor depuis les paramètres Piko de X, attendre l'établissement de la connexion, puis utiliser X aussi normalement que possible. Lorsque le mode Tor est actif, le trafic réseau **couvert par la solution retenue** doit emprunter Tor, sans connexion directe pouvant exposer l'adresse IP publique. L'objectif est une expérience de type VPN dédié à X, **sans application VPN tierce**, sans root, sans Orbot, sans proxy externe et sans relais propriétaire.

**Ne jamais confondre l'objectif de couverture complète avec une couverture effectivement prouvée.** Toute limite (trafic non intercepté, DNS, IPv6, UDP/QUIC, processus auxiliaires, notifications, etc.) doit être identifiée, documentée et testée. Si l'exigence de protection ne peut pas être respectée, ne pas simuler une sécurité inexistante.

## 2. Fonctionnalités cibles

### 2.1. Arti intégré dans l'application Android

- Évaluer la faisabilité d'embarquer Arti dans l'APK/APKM produit par Morphe.
- Étudier les bibliothèques natives Android nécessaires (ABI, chargement, packaging), ainsi qu'une interface JNI/FFI entre Kotlin/Java et Rust.
- Pouvoir démarrer, arrêter et redémarrer Arti directement depuis l'application patchée.
- Gérer l'établissement des circuits Tor, l'état de connexion et les erreurs.
- Fonctionner sans root, Orbot, VPN externe ni serveur proxy tiers.
- Prendre en compte les licences et obligations de distribution de tous les composants.

### 2.2. Acheminement réseau et protection contre les fuites

Comparer sérieusement un proxy SOCKS intégré associé à des hooks réseau de X à une solution utilisant `Android VpnService`.

Si `VpnService` est retenu, étudier et valider :

- L'interception du seul trafic de X lorsque c'est possible, et les limites de cette isolation.
- La redirection des connexions compatibles vers Tor/Arti.
- La résolution DNS sans fuite.
- Le comportement IPv4, IPv6 et TCP.
- Le traitement explicite des limites de Tor concernant UDP/QUIC : blocage, adaptation ou autre mécanisme justifié ; jamais de contournement direct silencieux.
- La protection **fail-closed** : lorsqu'elle est activée, aucun trafic couvert ne doit partir directement si Tor est indisponible.
- L'absence de boucles entre la capture VPN et les sockets qu'Arti utilise pour rejoindre Tor.

**Un simple proxy SOCKS n'est pas un VPN complet.** Ne pas déclarer que tout X passe par Tor tant que les différentes piles et voies réseau de X n'ont pas été auditées et éprouvées.

### 2.3. Réglages Piko dans X

Ajouter une section telle que **Privacy → Tor Network** en respectant le système de préférences existant.

Fonctions attendues :

- Activer ou désactiver Tor.
- Afficher les états : **désactivé**, **connexion en cours**, **connecté**, **erreur**.
- Redémarrer la connexion Tor.
- Afficher des erreurs utiles sans divulguer d'informations sensibles.
- Activer ou désactiver une protection empêchant les connexions hors Tor.
- Éviter que l'interface indique « connecté » tant que la protection réseau réelle n'est pas établie.

### 2.4. Compatibilité et performances

- Préserver autant que possible le fonctionnement normal de X et des autres patchs Piko.
- Respecter les cycles de vie Android, les restrictions d'arrière-plan, les services et les permissions.
- Limiter l'impact sur la batterie, la mémoire et le démarrage.
- Assurer la compatibilité avec le patching Morphe et le packaging APK/APKM.
- Tester spécialement : connexion au compte, images, vidéos, messages privés, notifications et protocoles incompatibles avec Tor.
- Décrire clairement les dégradations ou fonctionnalités impossibles à préserver.

## 3. Étudier le dépôt réel avant de modifier le code

Ne pas inventer l'architecture du projet ni les chemins présents. Inspecter le code, les versions et les conventions actuelles, notamment, **si ces chemins existent** :

- `patches/src/main/kotlin/app/crimera/patches/twitter/`
- `patches/src/main/kotlin/app/crimera/patches/twitter/misc/extension/ExtensionPatch.kt`
- `extensions/twitter/src/main/java/app/morphe/extension/twitter/`
- `extensions/twitter/src/main/java/app/morphe/extension/twitter/settings/Settings.java`
- `extensions/twitter/src/main/java/app/morphe/extension/twitter/settings/ScreenBuilder.java`
- Les fichiers Gradle, manifestes Android, mécanismes d'injection et d'empaquetage des extensions.

Déterminer où déclarer proprement un patch optionnel `TorNetworkPatch`, où injecter le code d'exécution, comment intégrer les artefacts natifs Rust et comment préserver la compatibilité avec les autres patchs.

## 4. Méthode de travail : quatre étapes

### Étape 1 — Étude de faisabilité (avant l'implémentation)

Établir si Arti et un acheminement réseau réellement protecteur peuvent fonctionner dans une application X modifiée par Morphe.

Comparer au minimum :

- **A — Arti embarqué + proxy SOCKS + hooks réseau de X.**
- **B — Arti embarqué + `Android VpnService` + tunnel local + acheminement TCP vers Tor.**
- **C — Architecture alternative**, si A ou B n'offrent pas une couverture, une sécurité ou une compatibilité suffisante.

Comparer les solutions sur la **couverture effective du trafic**, les risques de fuites et les limites de sécurité, la compatibilité Android/Morphe, les performances, les restrictions du système et la complexité de maintenance. Examiner les impossibilités ou contraintes de `VpnService` dans un APK tiers patché, les permissions et le risque de boucles réseau. Recommander une architecture uniquement après cette analyse.

### Étape 2 — Conception

Définir une architecture détaillée :

- Composants et responsabilités (client Arti, pont JNI/FFI, couche de routage, contrôle fail-closed, état, interface).
- Manifestes, permissions, services Android et éventuels processus.
- Points d'injection dans Piko et interactions avec Morphe.
- Flux réseau, gestion des erreurs, démarrage/arrêt, circuits et DNS.
- Stratégie de compilation Rust/Android, packaging par ABI et distribution.
- Limites assumées et modèle de menace vérifiable.

### Étape 3 — Implémentation progressive

- Ajouter des changements modulaires, maintenables, optionnels et cohérents avec les conventions Piko.
- Avancer par incréments testables, en évitant de casser les patchs existants.
- Ne pas réécrire Piko ni remplacer le fonctionnement de Morphe.
- Ne pas toucher à Instagram dans la première version.
- Ne pas considérer un bouton, une préférence ou un proxy partiel comme un prototype de protection réseau fonctionnel.
- Ne pas contourner silencieusement Tor en cas d'échec : privilégier le **fail-closed** lorsque la protection est activée.

### Étape 4 — Validation et compte rendu

Vérifier et distinguer explicitement **tests réellement exécutés**, **tests non exécutés** et **tests nécessitant un appareil Android** :

- Compilation du projet et génération des patchs Morphe.
- Présence, chargement et fonctionnement des bibliothèques natives dans l'APK/APKM final.
- Démarrage réel d'Arti sur Android et établissement de circuits Tor.
- Vérification de l'acheminement des connexions de X vers Tor (pas seulement l'ouverture d'un port SOCKS).
- Absence de fuites DNS, IPv4 et IPv6 dans les scénarios effectivement testés.
- Absence de trafic direct lors d'une coupure de Tor en mode fail-closed.
- Comportement des fonctionnalités essentielles de X (authentification, médias, messages, notifications).
- Compatibilité avec les autres patchs, différentes versions Android et restrictions d'arrière-plan pertinentes.

Ne jamais inventer de résultat de test ni annoncer « aucune fuite » sur la seule base d'une revue de code.

## 5. Contraintes de sécurité et d'honnêteté technique

1. **Protection réelle plutôt que cosmétique.** Aucun faux bouton « VPN », fausse connexion Tor ou affirmation globale de confidentialité sans preuves.
2. **Aucune sortie directe inattendue.** Si Tor est activé mais indisponible, ne pas laisser passer directement le trafic censé être protégé ; tout périmètre non protégé doit être clairement signalé.
3. **Couverture mesurée.** Les hooks SOCKS, `VpnService`, le DNS, IPv6, UDP/QUIC et les connexions hors des chemins connus demandent une vérification indépendante.
4. **Pas d'infrastructure propriétaire intermédiaire.** L'objectif est un client Tor autonome dans X, sans application VPN externe.
5. **Confidentialité ≠ anonymat du compte.** Utiliser un compte X authentifié peut identifier l'utilisateur par son compte, ses actions et d'autres métadonnées, même si son IP est masquée aux serveurs de destination.
6. **Licences et compatibilité.** Respecter les licences des composants et du projet ; signaler toute incompatibilité technique ou juridique.
7. **Transparence des blocages.** Si la sécurité exigée n'est pas réalisable avec Morphe ou Android, expliquer précisément pourquoi et proposer une alternative réaliste au lieu de la simuler.

## 6. Résultat recherché et comportement attendu des agents

**Résultat final visé :** un patch Piko optionnel permettant de lancer Tor/Arti depuis les réglages de X, avec une intégration Android réelle, une couverture réseau démontrée dans un périmètre défini, une protection contre les fuites et des tests reproductibles.

Lors des futures conversations sur ce projet :

- Garder cet objectif et les contraintes ci-dessus comme **contexte permanent**.
- Lire le code réel et l'état actuel du dépôt avant de proposer des modifications.
- Ne pas confondre ce cahier des charges avec des fonctionnalités déjà implémentées.
- Conserver les décisions d'architecture et les résultats de tests dans la documentation du dépôt au fur et à mesure, plutôt que de dépendre de la mémoire d'une conversation.
- Si l'utilisateur demande de commencer le chantier, **commencer par l'étude de faisabilité**, puis concevoir, implémenter un premier prototype testable et valider selon les étapes précédentes.
- Expliquer honnêtement toute limite, incertitude, régression ou impossibilité rencontrée.
