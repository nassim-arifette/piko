# Instructions permanentes du projet — Piko Tor / Arti (Android / X)

> **Portée :** ce document décrit le contexte, la vision, les contraintes et la méthode de travail à conserver dans toutes les sessions d'agents (notamment Devin) intervenant sur ce dépôt.
>
> **Important :** il s'agit d'un **objectif de développement**, et non d'une fonctionnalité déjà implémentée ou dont la sécurité serait démontrée. Ne jamais présenter une capacité comme opérationnelle ou sans fuite réseau sans validation effective.
>
> **Priorité :** toute tâche en rapport avec Tor, Arti, le réseau ou les patchs X doit rester cohérente avec ce document. Pour les autres tâches du dépôt, ne pas imposer inutilement le périmètre Tor.

## 1. Contexte, choix d'architecture et priorité absolue

- **Dépôt du fork :** https://github.com/nassim-arifette/piko
- **Projet existant :** Piko est un ensemble de patchs Morphe pour les applications Android Twitter/X et Instagram. Morphe applique les patchs à l'APK/APKM ; Piko n'est pas lui-même le moteur de patching.
- **Périmètre initial :** X uniquement, pas Instagram.
- **Fonctionnalité visée :** un patch Piko optionnel (nom envisagé : `TorNetworkPatch`) donnant accès aux contrôles Tor dans les paramètres de X.
- **Objectif de confidentialité :** masquer l'IP publique de l'utilisateur vis-à-vis des serveurs de X en faisant passer les connexions concernées par le réseau Tor, via Arti (client Tor écrit en Rust).
- **Priorité NON NÉGOCIABLE :** lorsqu'un mode de protection est activé, **mieux vaut que X n'ait plus Internet plutôt qu'il se connecte directement sans Tor**. Aucun fallback silencieux vers le réseau direct, y compris lors du démarrage, d'une erreur Tor ou d'une panne du VPN.

### Décision de conception retenue : deux APK, un même projet

La **voie privilégiée** est de maintenir deux applications Android distinctes, développées dans ce même dépôt, plutôt qu'un seul APK qui héberge X *et* le fournisseur VPN :

1. **APK X + Piko (Morphe)** : conserve les patchs X existants, expose les paramètres, l'état et les commandes de la fonctionnalité Tor. L'app X **n'héberge pas le service VPN**.
2. **APK compagnon « Piko Tor »** : application maintenue par ce projet, autonome, sans Orbot ni fournisseur de VPN/proxy tiers, qui héberge `VpnService`, Arti et le moteur de transfert réseau, avec un identifiant d'application/UID Android distinct de X.

Le fait d'avoir deux APK **ne suffit pas** à garantir le blocage : la protection contre la disparition du tunnel doit reposer sur le mode Android **« VPN permanent » + « Bloquer les connexions sans VPN »**, une configuration système que l'utilisateur devra activer et vérifier. Cette configuration peut affecter les autres applications du téléphone/profil.

**Pourquoi cette séparation :** si X et `VpnService` partagent l'UID du fournisseur VPN dans un même APK, ils ne bénéficient pas de la même barrière de verrouillage système indépendante ; le fournisseur VPN doit pouvoir accéder au réseau sous-jacent pour joindre Tor. La conception à deux UID doit être validée par des tests sur les versions Android cibles.

L'ancienne idée « Tor + VPN dans le même APK X » reste un sujet d'étude, **pas l'architecture de référence pour le mode strict**. Ne la retenir que si une barrière équivalente contre les fuites lors d'un crash/retrait du VPN est effectivement démontrée.

**Limite de la promesse :** l'objectif « aucune connexion directe quand la protection est active et correctement configurée » doit être démontré sur des appareils réels ; ne jamais promettre le risque zéro sur tous les appareils, notamment si le verrouillage système est désactivé, l'autorisation VPN retirée, l'APK compagnon désinstallé ou le système compromis.

## 2. Fonctionnalités cibles

### 2.1. Piko Tor : application compagnon et moteur Tor/Arti

- Étudier la création d'un **module/application Android autonome** dans le même dépôt, distinct des extensions de code injectées dans X.
- Évaluer la faisabilité d'embarquer Arti en Rust dans cette application, les ABI Android nécessaires et l'interface JNI/FFI avec Kotlin/Java.
- Évaluer **Onionmasq** ou un équivalent auditable pour relier un tunnel TUN Android à Arti ; **ne pas supposer** sa compatibilité ou sa maintenance sans vérification.
- Démarrer, maintenir, relancer et arrêter proprement les composants, avec gestion des circuits, des erreurs, de la batterie, des restrictions Android et des redémarrages.
- Utiliser une communication **inter-applications sécurisée** entre X/Piko et Piko Tor (permissions, intents/IPC explicites, authentification adaptée aux signatures réellement utilisées par Morphe). Ne jamais autoriser une commande externe arbitraire à désactiver la protection.
- Ne pas exiger de root, d'Orbot, d'un VPN commercial ni d'un serveur proxy propriétaire.
- Vérifier la compatibilité des licences et les exigences de publication des composants Rust/Android.

### 2.2. Fail-closed strict et protection contre les fuites d'IP

**Invariants de sécurité souhaités :**

1. **Tor opérationnel + VPN actif + verrouillage Android actif** → X peut accéder au réseau **uniquement via Tor** dans le périmètre réseau validé.
2. **Tor indisponible mais tunnel VPN présent** → les connexions de X sont **bloquées** ; jamais de sortie directe ni de contournement IPv4, IPv6, DNS, UDP ou QUIC.
3. **Service VPN arrêté, tué, planté ou tunnel détruit** → le verrouillage VPN **géré par Android** doit bloquer les connexions de X jusqu'au rétablissement du VPN.
4. **Au démarrage / après redémarrage / pendant la reconnexion** → ne pas considérer la protection active ni autoriser X à transmettre tant que les conditions nécessaires ne sont pas réunies. Auditer les requêtes envoyées avant l'initialisation des hooks.
5. **Autorisation VPN révoquée, VPN permanent désactivé ou application désinstallée** → ne pas prétendre que la barrière système subsiste ; détecter et signaler la perte de protection lorsque c'est possible. Évaluer une garde supplémentaire dans X, sans l'appeler « garantie » si elle ne couvre pas toutes les piles réseau.
6. **L'utilisateur désactive explicitement la protection** → afficher clairement le risque de connexion directe ; ne jamais désactiver le verrouillage ou basculer en direct implicitement.

**Exigences Android :**

- Étudier `VpnService`, les options système « VPN permanent » / « Bloquer les connexions sans VPN » et leurs différences selon Android/OEM. **Une application grand public ne doit pas présumer pouvoir activer ce verrouillage automatiquement** ; prévoir un guide de configuration utilisateur.
- Utiliser et tester `VpnService.Builder.addAllowedApplication()` pour cibler précisément le package installé de X lorsque c'est adapté. **Vérifier en mode verrouillage** le sort des autres applications : le VPN par application et le verrouillage Android peuvent les priver d'Internet. Évaluer un profil Android séparé si l'utilisateur veut isoler X du reste du téléphone.
- Vérifier que X et Piko Tor ont effectivement **des UID différents** ; ne pas utiliser un UID partagé ni supposer un nom de package immuable (clones/renommage).
- Protéger les sockets sortants nécessaires à Arti contre une boucle VPN, tout en s'assurant qu'**aucun socket de X** n'obtient un bypass direct du système VPN.
- Transférer les flux TCP via Tor, traiter les DNS sans fuite et **bloquer ou gérer explicitement** les protocoles non compatibles (notamment UDP/QUIC, IPv6) ; jamais de fallback direct.
- Vérifier les permissions, composants exportés, état du VPN, configuration réseau, reconnexion et gestion des interruptions.

**Distinction fondamentale :** le fail-closed du moteur Tor lorsque le TUN existe ne protège **pas** contre la disparition du TUN. L'objectif strict requiert un verrouillage indépendant fourni par Android et effectivement activé.

**Un SOCKS local n'est pas un VPN complet.** Toute couverture réseau déclarée doit être vérifiée avec des tests négatifs (trafic qui *ne doit pas* sortir).

### 2.3. Réglages Piko dans X

Ajouter une section telle que **Privacy → Tor Network** en respectant les préférences existantes de Piko.

- Afficher la présence/version de l'application compagnon « Piko Tor » et proposer l'installation/la configuration manuelle si elle manque.
- Guider l'utilisateur pour donner son consentement VPN et activer **VPN permanent** puis **Bloquer les connexions sans VPN** dans Android ; ne pas présenter ces actions système comme automatiquement effectuées.
- Afficher un état honnête : **non installé**, **VPN/lockdown non configuré**, **connexion Tor en cours**, **protégé**, **Tor indisponible — trafic bloqué**, **protection perdue / erreur**.
- Permettre de démarrer/redémarrer Tor si le contrôle inter-applications est validé, sans créer de fenêtre de connexion directe.
- Ne montrer **« protégé »** qu'avec des signaux suffisamment fiables de l'état Tor, du VPN et de la configuration de verrouillage ; autrement indiquer que l'état n'est pas vérifié.
- Pour toute désactivation volontaire, expliquer le risque pour l'IP publique et exiger une action explicite. **Jamais de désactivation automatique de la protection pour « réparer » la connexion.**
- Ne pas exposer dans les journaux des jetons X, des adresses sensibles, des clés ou d'autres secrets.

### 2.4. Compatibilité et performances

- Préserver autant que possible le fonctionnement normal de X et des autres patchs Piko.
- Respecter les cycles de vie Android, les restrictions d'arrière-plan, les services et les permissions.
- Limiter l'impact sur la batterie, la mémoire et le démarrage.
- Assurer la compatibilité avec le patching Morphe et le packaging APK/APKM.
- Tester spécialement : connexion au compte, images, vidéos, messages privés, notifications et protocoles incompatibles avec Tor.
- Décrire clairement les dégradations ou fonctionnalités impossibles à préserver.
- Tenir compte du fait qu'Android n'autorise normalement **qu'un VPN actif par profil/utilisateur** et que le verrouillage système peut couper le réseau d'autres applications ; le communiquer explicitement.
- Prévoir l'installation de **deux APK** et la configuration système par l'utilisateur, même si les sources restent dans un unique dépôt.

## 3. Étudier le dépôt réel avant de modifier le code

Ne pas inventer l'architecture du projet ni les chemins présents. Inspecter le code, les versions et les conventions actuelles, notamment, **si ces chemins existent** :

- `patches/src/main/kotlin/app/crimera/patches/twitter/`
- `patches/src/main/kotlin/app/crimera/patches/twitter/misc/extension/ExtensionPatch.kt`
- `extensions/twitter/src/main/java/app/morphe/extension/twitter/`
- `extensions/twitter/src/main/java/app/morphe/extension/twitter/settings/Settings.java`
- `extensions/twitter/src/main/java/app/morphe/extension/twitter/settings/ScreenBuilder.java`
- Les fichiers Gradle, les manifestes Android et les mécanismes d'injection et de packaging Morphe.

Déterminer :

- Où déclarer le patch optionnel `TorNetworkPatch`, comment ajouter les réglages à X et intégrer les commandes/états provenant de Piko Tor.
- Comment créer un **nouvel APK compagnon** sans casser le build ou les patchs Piko existants.
- Comment signer et installer les deux APK, reconnaître le package réel de X, sécuriser les communications et éviter une dépendance à des signatures partagées non garanties.
- Comment empaqueter Arti et les éventuelles bibliothèques Rust, par ABI et version Android.
- Quels réglages système doivent être configurés **manuellement** avant qu'un état « protégé » soit valide.

## 4. Méthode de travail : quatre étapes

### Étape 1 — Étude de faisabilité (avant l'implémentation)

Comparer au minimum :

- **A — Un seul APK X/Piko avec Arti + hooks SOCKS** : risques de trafic non intercepté, limites de couverture.
- **B — Un seul APK X/Piko avec Arti + `VpnService`** : limites dues à l'UID du fournisseur VPN et au verrouillage indépendant.
- **C — Approche préférée : deux APK distincts** (X + Piko, et Piko Tor avec `VpnService` + Arti) **avec verrouillage système Android obligatoire** pour le mode de protection strict.

Examiner Onionmasq et les composants équivalents, l'isolation par profil Android, le comportement du VPN par application en mode verrouillage, les processus réseau de X et les contraintes Morphe. Produire un tableau de risques et **indiquer tout point non démontré**. Ne pas réintroduire une architecture plus faible sans expliquer pourquoi elle préserverait les mêmes garanties.

### Étape 2 — Conception

Définir précisément :

- Les deux APK, leurs rôles, signatures/UID, manifestes, services, permissions et protocole IPC.
- La machine à états des connexions Tor, du tunnel VPN et de l'indicateur de protection ; comportement à chaque transition, crash et retrait d'autorisation.
- Le démarrage sûr (aucune fenêtre d'accès réseau direct non prévue), le DNS, IPv4/IPv6, TCP, UDP/QUIC, les sockets Arti et le blocage du trafic incompatible.
- La compilation Rust/Android, le packaging ABI, l'impact batterie/mémoire et les parcours d'installation/configuration système.
- Une procédure de validation reproductible et des critères objectifs pour dire « trafic de X bloqué » / « trafic de X via Tor ».

### Étape 3 — Implémentation progressive

- Commencer par un premier jalon vérifiable, sans annoncer une protection complète trop tôt.
- Développer le patch Piko **optionnel** et le compagnon Android **distinct** de façon modulaire.
- Ne pas réécrire Piko, remplacer Morphe ni modifier Instagram pour la première version.
- Préférer le **fail-closed par défaut** dès que la protection est demandée. Refuser un fallback direct même en cas de difficultés de connexion ou d'incompatibilité média.
- Ne pas confondre affichage d'un bouton, connexion d'un proxy, démarrage d'Arti et protection effective.

### Étape 4 — Validation, y compris scénarios négatifs

Distinguer **tests réellement effectués**, **non exécutés** et **tests nécessitant un appareil Android**. Tester au minimum :

1. Build Piko/Morphe et build de Piko Tor ; installation des deux APK sur les ABI Android supportées.
2. Identités de package et UID effectivement distinctes, IPC et refus des requêtes non autorisées.
3. Connexion réelle d'Arti et sortie du trafic de X via Tor (observations réseau indépendantes, sans confondre un port ouvert et un tunnel sûr).
4. Fuites DNS, IPv4, IPv6, connexions natives, UDP/QUIC, WebView et processus auxiliaires de X.
5. **Coupure volontaire de Tor avec TUN maintenu** : aucune connexion directe de X.
6. **Crash/kill du `VpnService` ou disparition du TUN** : avec VPN permanent et verrouillage actif, aucune connexion directe de X.
7. Redémarrage de l'appareil, démarrage avant Tor, changement Wi-Fi ↔ mobile, mode avion, arrière-plan et restrictions batterie.
8. Retrait du consentement VPN, désactivation du verrouillage, changement de fournisseur VPN, désinstallation du compagnon : documenter précisément **ce qui n'est plus garanti** et empêcher toute fausse indication « protégé ».
9. **VPN par application + verrouillage** : vérifier le trafic des autres applications, alerter sur l'impact et examiner le profil Android si nécessaire.
10. Authentification X, médias, messages privés, notifications et non-régression des autres patchs.

Ne jamais inventer un test, une mesure, un état sécurisé ou une absence globale de fuite.

## 5. Contraintes de sécurité et d'honnêteté technique

1. **Fail-closed strict prioritaire.** X ne doit pas récupérer automatiquement Internet sans Tor si Arti ou le VPN ne fonctionne plus. Les limites réelles de cette exigence dépendent de la configuration et du comportement vérifié d'Android.
2. **Deux APK de référence.** La séparation X / Piko Tor et la présence d'un verrouillage VPN Android activé sont les prérequis du modèle de protection recherché. « Deux APK » sans verrouillage actif **n'est pas suffisant**.
3. **Aucune fausse garantie.** Ne jamais prétendre pouvoir garantir à 100 % l'absence de fuite d'IP sur tous les appareils ou si l'utilisateur retire/désactive les protections système.
4. **Pas de bypass silencieux.** Ne jamais contourner Tor pour faire fonctionner X, les notifications, les médias, UDP ou les DNS. Bloquer plutôt que basculer directement.
5. **Séparer identité de compte et IP.** Même avec Tor, un compte X connecté et les actions de l'utilisateur peuvent permettre à X de reconnaître ce compte. Le fournisseur d'accès voit normalement la connexion au réseau Tor.
6. **Infrastructure indépendante.** Pas d'Orbot obligatoire, de VPN tiers, de serveur proxy propriétaire ni d'exigence de root. L'APK compagnon Piko Tor est un composant **du projet**, pas un fournisseur externe.
7. **Sécurité du contrôle.** Ne pas permettre à un intent, composant exporté ou autre app de désactiver le verrouillage / la protection sans autorisation explicite.
8. **Licences et compatibilité.** Respecter GPLv3 et les licences des composants ; documenter les incompatibilités et contraintes Android ou Morphe.
9. **Documentation honnête de l'installation.** Indiquer le consentement VPN, le verrouillage système et son impact potentiel sur la connectivité des autres applications. Ne jamais décrire un réglage non effectué comme actif.

## 6. Résultat recherché et comportement attendu des agents

**Résultat final visé :** Piko ajoute à X une interface Tor et une intégration sécurisée avec une application compagnon **Piko Tor** fournissant `VpnService` et Arti, avec un **fail-closed strict** appuyé sur le verrouillage Android « VPN permanent + Bloquer les connexions sans VPN », puis démontré par des tests.

Lors des futures sessions d'agent :

- Conserver la **priorité à l'absence de connexion directe** plutôt qu'à la disponibilité de X.
- Traiter la **séparation de X et du moteur VPN en deux APK** comme architecture de référence, sans considérer qu'elle suffit à elle seule à sécuriser le trafic.
- Lire le code réel et l'état du dépôt avant les modifications ; conserver le fonctionnement des patchs existants et le périmètre X uniquement.
- Ne pas présenter ce cahier des charges comme un développement déjà réalisé.
- Documenter les décisions, les dépendances, les versions, les preuves et les tests dans le dépôt.
- Commencer par la faisabilité, puis concevoir, implémenter et valider progressivement, en signalant sans ambiguïté les fonctions impossibles ou les risques résiduels.

### Références techniques pour la phase de faisabilité

- Android — VPN, VPN permanent et blocage des connexions hors VPN : https://developer.android.com/develop/connectivity/vpn
- Android — VpnService, état du verrouillage et bypass de sockets du moteur VPN : https://developer.android.com/reference/android/net/VpnService
- Android — VPN par application : https://developer.android.com/reference/android/net/VpnService.Builder
- Tor Project — Arti : https://gitlab.torproject.org/tpo/core/arti
- Tor Project — Onionmasq (à examiner, non présumé compatible) : https://gitlab.torproject.org/tpo/core/onionmasq

