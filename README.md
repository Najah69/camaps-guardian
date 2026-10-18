# CamAPS Guardian

**Un gardien de coin de rue pour CamAPS FX.** Une petite application Android qui vérifie, à
intervalle régulier, que le Bluetooth de votre téléphone est allumé et que CamAPS FX tourne
toujours — et qui tente de rétablir ce qui ne l'est plus.

![Licence MIT](https://img.shields.io/badge/licence-MIT-2ea44f)
![Android 8.0 et plus](https://img.shields.io/badge/Android-8.0%20et%20plus-3ddc84)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7f52ff)
![Sans root](https://img.shields.io/badge/root-facultatif-lightgrey)

**[⬇︎ Télécharger la dernière version](https://github.com/Najah69/camaps-guardian/releases/latest)**

---

## 🛑 À lire avant tout

> **CamAPS Guardian n'est pas un dispositif médical.**
>
> Il ne calcule aucune dose, ne commande pas la pompe, ne lit pas la glycémie et **ne remplace
> ni CamAPS FX, ni ses alarmes**. Il ne fait qu'une chose : essayer de maintenir en vie la
> liaison entre votre téléphone, votre capteur et l'application.
>
> **Gardez toutes les alarmes de CamAPS FX activées.** Ce sont elles — et elles seules — qui
> vous préviennent d'une perte de connexion ou d'une glycémie dangereuse. CamAPS Guardian est
> un filet supplémentaire, jamais le filet principal.
>
> Ce projet est fourni **tel quel**, sans aucune garantie, sous licence MIT. Il n'est ni édité,
> ni validé, ni soutenu par CamDiab Ltd. Si vous n'êtes pas à l'aise avec l'installation d'une
> application hors du Play Store, **faites-vous aider** : une erreur de manipulation sur un
> téléphone qui pilote une boucle fermée n'est pas anodine.

---

## Sommaire

**Partie 1 — pour les personnes diabétiques de type 1 sous CamAPS FX**
1. [En deux mots](#1-en-deux-mots)
2. [À qui s'adresse ce projet](#2-à-qui-sadresse-ce-projet)
3. [Pourquoi CamAPS FX peut s'arrêter](#3-pourquoi-camaps-fx-peut-sarrêter)
4. [Ce que fait CamAPS Guardian](#4-ce-que-fait-camaps-guardian)
5. [Ce qu'il ne fait pas](#5-ce-quil-ne-fait-pas)
6. [Ce qu'il faut sur votre téléphone](#6-ce-quil-faut-sur-votre-téléphone)
7. [Installation, pas à pas](#7-installation-pas-à-pas)
8. [Les réglages constructeur, souvent oubliés](#8-les-réglages-constructeur-souvent-oubliés)
9. [Comment savoir que ça marche](#9-comment-savoir-que-ça-marche)
10. [Les réglages, un par un](#10-les-réglages-un-par-un)
11. [Lire le journal](#11-lire-le-journal)
12. [Questions fréquentes](#12-questions-fréquentes)
13. [Vie privée](#13-vie-privée)
14. [Désinstallation](#14-désinstallation)

**Partie 2 — pour les développeurs**
15. [Architecture](#15-architecture)
16. [La stratégie de survie](#16-la-stratégie-de-survie)
17. [Pourquoi `targetSdk 32`](#17-pourquoi-targetsdk-32)
18. [Détecter CamAPS FX sans root](#18-détecter-camaps-fx-sans-root)
19. [Contrôler le Bluetooth en couches](#19-contrôler-le-bluetooth-en-couches)
20. [Compiler](#20-compiler)
21. [Carte du code](#21-carte-du-code)
22. [Ce qui est vérifié, ce qui ne l'est pas](#22-ce-qui-est-vérifié-ce-qui-ne-lest-pas)
23. [Contribuer](#23-contribuer)
24. [Licence](#24-licence)

---

# Partie 1 — pour les personnes diabétiques de type 1 sous CamAPS FX

## 1. En deux mots

CamAPS FX a besoin de deux choses pour faire son travail : **être lancé**, et **parler à votre
capteur en Bluetooth**. Android, de son côté, fait tout pour économiser la batterie : il endort
les applications, coupe les services, et parfois le Bluetooth décroche.

CamAPS Guardian est une application qui **reste éveillée** et qui, toutes les 5, 10, 15 ou
30 minutes selon votre choix, va voir :

- le Bluetooth est-il allumé ?
- un appareil est-il réellement connecté ?
- CamAPS FX tourne-t-il encore ?

Si quelque chose ne va pas, elle essaie de le remettre en route — et elle **écrit tout** dans un
journal que vous pouvez lire.

## 2. À qui s'adresse ce projet

- Vous êtes **diabétique de type 1** et vous utilisez **CamAPS FX** sur un téléphone **Android**.
- Votre téléphone est celui qui porte la boucle fermée : il reste près de vous, jour et nuit.
- Il vous est déjà arrivé de retrouver CamAPS FX fermé, ou le Bluetooth éteint, sans savoir
  pourquoi ni depuis quand.

Si vous êtes sous CamAPS FX sur iPhone, ce projet ne vous concerne pas : il est écrit pour
Android uniquement.

## 3. Pourquoi CamAPS FX peut s'arrêter

Trois choses arrivent, et aucune n'est de votre faute :

**L'application est tuée.** Android ferme les applications en arrière-plan pour libérer de la
mémoire, ou parce qu'un fabricant a décidé d'être particulièrement agressif (Xiaomi, Huawei,
Samsung, Oppo, MediaTek en général). CamAPS FX disparaît alors sans bruit.

**Le Bluetooth s'éteint.** Une économie d'énergie, un redémarrage, une mise à jour, et le
Bluetooth n'est plus là. CamAPS FX ne peut plus joindre le capteur.

**La liaison décroche.** Le capteur et le téléphone se perdent de vue. CamAPS FX essaie de se
reconnecter tout seul — mais si l'application est en arrière-plan, Android peut l'en empêcher.

CamAPS FX a ses propres alarmes, et elles fonctionnent. CamAPS Guardian ne les remplace pas : il
ajoute une vérification **régulière et indépendante**, qui ne dépend pas de l'application
surveillée pour fonctionner.

## 4. Ce que fait CamAPS Guardian

À chaque ronde, dans cet ordre :

| Étape | Ce qui est vérifié | Ce qui est tenté |
|---|---|---|
| 1 | Le Bluetooth est-il allumé ? | Rallumage direct, puis via root ou Shizuku, sinon alerte à toucher |
| 2 | Combien d'appareils sont connectés ? | Si zéro pendant plusieurs rondes : CamAPS ramené au premier plan ; option : cycle Bluetooth (coupure puis rallumage) |
| 3 | CamAPS FX tourne-t-il ? | Si muet deux rondes de suite : relance par l'écran de lancement |
| 4 | Batterie et veille | Rien de destructeur : un rappel si la batterie descend sous 15 % |
| 5 | La chaîne d'alarmes tient-elle ? | Réarmement, réécriture de la notification permanente |

Elle continue **quoi qu'il arrive** : elle ne s'arrête jamais d'elle-même, même à 3 % de
batterie, même en veille prolongée, même après un redémarrage du téléphone.

## 5. Ce qu'il ne fait pas

Dire ce qu'un outil ne fait pas est aussi important que le reste.

- **Il ne dose pas l'insuline.** Il ne touche ni à la pompe, ni aux paramètres de CamAPS FX.
- **Il ne remplace pas les alarmes de CamAPS FX.** Gardez-les toutes activées.
- **Il n'est pas infaillible.** Aucune application Android ne peut être totalement
  increvable sans être installée comme application système (root) ou comme propriétaire de
  l'appareil. CamAPS Guardian fait le maximum de ce qui est possible sans privilège, et le
  journal dit honnêtement ce qui a fonctionné.
- **Sans root ni Shizuku, il ne peut pas rallumer le Bluetooth tout seul** sur Android 13 et
  plus : Android l'interdit aux applications ordinaires. Il affiche alors un écran d'alerte
  qu'il faut toucher. C'est une limite du système, pas un défaut de l'application.
- **Il ne détecte pas toujours CamAPS FX.** S'il n'a pas l'accès aux notifications et que
  CamAPS FX n'est pas passé au premier plan récemment, son état est « indéterminé » — et dans
  ce cas il ne relance rien, par prudence.
- **Il n'est pas publié sur le Play Store**, et ne peut pas l'être dans cet état (voir
  [§17](#17-pourquoi-targetsdk-32)). Vous l'installez à la main, en connaissance de cause.

## 6. Ce qu'il faut sur votre téléphone

- Android **8.0 ou plus récent**.
- **CamAPS FX installé et fonctionnel.**
- Le **débogage USB** n'est pas nécessaire : c'est juste plus pratique pour installer.
- Environ **8 Mo** d'espace libre.

Rien d'autre. Pas de compte, pas de connexion Internet, pas de serveur.

## 7. Installation, pas à pas

### 7.1 Récupérer l'application

Téléchargez [`app-release.apk`](https://github.com/Najah69/camaps-guardian/releases/latest)
depuis la page **Releases** de ce dépôt, ou compilez-la vous-même (voir [§20](#20-compiler)).

### 7.2 Autoriser l'installation depuis une source inconnue

Android bloque par défaut les applications qui ne viennent pas du Play Store. C'est une
protection normale ; vous l'ouvrez pour cette installation seulement.

1. Ouvrez le fichier `app-release.apk` depuis votre gestionnaire de fichiers.
2. Android propose « Ouvrir avec » ou affiche un message de blocage.
3. Touchez **Paramètres**, puis autorisez votre gestionnaire de fichiers (ou votre navigateur)
   à « installer des applications inconnues ».
4. Revenez en arrière et touchez **Installer**.

> Vous verrez peut-être « Application conçue pour une version plus ancienne d'Android ».
> C'est normal et volontaire : voir [§17](#17-pourquoi-targetsdk-32). Cela ne vous empêche pas
> de l'installer, et cela n'affecte pas son fonctionnement.

### 7.3 Accorder les autorisations

Ouvrez **CamAPS Guardian**. La section **AUTORISATIONS** de l'écran liste ce qui manque. Touchez
chaque ligne pour ouvrir le réglage Android correspondant :

| Autorisation | Pourquoi | Obligatoire ? |
|---|---|---|
| **Notifications** | Afficher la surveillance en cours et les alertes | Oui |
| **Bluetooth** | Lire l'état et les appareils connectés | Oui |
| **Batterie sans restriction** | Empêcher Android d'endormir la ronde | **Oui, la plus importante** |
| **Afficher par-dessus les autres applis** | Ramener CamAPS au premier plan sans que vous ayez à le faire | Fortement conseillé |
| **Accès aux notifications** | Savoir si CamAPS FX est vivant (sa notification permanente) | Fortement conseillé |
| **Alarmes exactes** | Déclencher la ronde à l'heure dite | Oui si proposé |
| **Accès aux stats d'usage** | Aide à dater le dernier passage au premier plan de CamAPS | Facultatif |

Ne sautez pas la ligne **Batterie sans restriction** : sans elle, votre téléphone finira par
endormir la ronde, et tout le reste devient inutile.

### 7.4 Activer la surveillance

Revenez sur l'écran principal et basculez l'interrupteur **Surveillance arrêtée** vers la
position active. Le libellé devient **Surveillance active**, et une notification permanente
apparaît dans la barre d'état : c'est le signe que le service tourne.

## 8. Les réglages constructeur, souvent oubliés

C'est la partie que presque tout le monde rate, et c'est celle qui décide si l'application
survivra à la nuit.

Chaque fabricant a sa propre couche d'économies d'énergie, **au-dessus** de celle d'Android.
Voici les noms à chercher dans les réglages de votre téléphone :

| Fabricant | À activer | À désactiver |
|---|---|---|
| **Xiaomi / Redmi / POCO** | « Démarrage automatique » pour CamAPS Guardian | « Économiseur de batterie » pour l'application |
| **Huawei / Honor** | « Lancer manuellement » / « Gérer manuellement » | Optimisation de l'application |
| **Samsung** | Retirer de « Applications en veille » | « Mettre l'application en veille » |
| **Oppo / Realme / OnePlus** | « Démarrage automatique » | « Optimisation de la batterie » |
| **Vivo** | « Démarrage en arrière-plan » | « Économie d'énergie élevée » |
| **MediaTek (dont Hammer, Ulefone)** | « Autostart » / « Démarrage auto » | « Veille en arrière-plan » |
| **Android nu (Pixel)** | Rien de spécial | Rien de spécial |

Le nom exact varie d'un modèle à l'autre. Cherchez dans les réglages les mots
**démarrage automatique**, **autostart**, **veille** ou **optimisation**.

> **Le test qui compte :** redémarrez votre téléphone, attendez deux minutes, puis ouvrez
> CamAPS Guardian. S'il affiche **Surveillance active** sans que vous ayez rien fait, votre
> configuration est bonne. Sinon, un réglage constructeur manque.

## 9. Comment savoir que ça marche

1. Ouvrez CamAPS Guardian.
2. Touchez **Vérifier maintenant** (section ACTIONS).
3. Lisez la ligne de résumé sous les boutons : elle doit ressembler à
   `BT actif · 2 connecté(s) · CamAPS actif · 78 %`.

Si elle affiche `CamAPS absent`, lisez [§12](#12-questions-fréquentes).

![Écran principal de CamAPS Guardian](docs/ecran-principal.png)

Puis laissez faire. Une ronde toutes les 10 minutes (par défaut) suffit à repérer un problème
sans vider la batterie.

## 10. Les réglages, un par un

### Fréquence

- **Ronde de contrôle** : `5 min`, `10 min`, `15 min`, `30 min`. C'est la fréquence à laquelle
  l'application regarde. **10 minutes** est un bon compromis pour un téléphone de boucle fermée.
  Descendez à 5 minutes si vous voulez être averti plus vite ; montez si la batterie souffre.
- **Rétablissement en UP** : la même échelle, mais pour les *relances*. Deux rondes consécutives
  peuvent détecter un problème ; cette limite évite de ramener CamAPS FX au premier plan toutes
  les deux minutes. **10 minutes** est un bon réglage.

### Interrupteurs

| Réglage | Effet | Conseil |
|---|---|---|
| **Rallumer le Bluetooth** | Tente de rallumer le Bluetooth s'il est éteint | Laissez activé |
| **Relancer CamAPS FX** | Ramène CamAPS au premier plan après deux rondes muettes | Laissez activé |
| **Relancer si connexion Bluetooth perdue** | Ramène CamAPS au premier plan quand aucun appareil n'est connecté — **seulement s'il est muet ou d'état inconnu** | Laissez activé |
| **Cycle Bluetooth agressif** | Coupe puis rallume le Bluetooth après 4 pertes de suite | À activer seulement si vous perdez souvent la liaison |

### Boutons

| Bouton | Ce qu'il fait |
|---|---|
| **Vérifier maintenant** | Lance une ronde immédiatement (sans attendre l'alarme) |
| **Relancer CamAPS** | Ramène CamAPS FX au premier plan tout de suite |
| **Détecter CamAPS FX** | Retrouve le nom de paquet de CamAPS FX et l'enregistre |
| **Effacer le journal** | Vide le journal (utile avant de signaler un problème) |

## 11. Lire le journal

Le journal est la partie la plus utile de l'application : il dit **ce qui s'est passé, quand, et
pourquoi**. Chaque ligne commence par une heure et une catégorie.

```
18/10 10:19:38 [service] service démarré (Android 15 (API 35))
18/10 10:19:38 [ronde] contrôle — démarrage
18/10 10:19:38 [camaps] relance par intent direct (com.camdiab.fx_alert.mgdl)
18/10 10:19:38 [ronde] notes : CamAPS muet (1×) : aucune notification active
18/10 10:19:38 [ronde] BT actif · 0 connecté(s) · CamAPS muet · 44 % · CamAPS relancé
18/10 10:19:38 [alarme] prochaine ronde 10:29 (alarmClock, 10 min)
```

| Catégorie | Signification |
|---|---|
| `[app]` | Démarrage de l'interface |
| `[service]` | Le service de surveillance démarre, termine une ronde, se réarme |
| `[ronde]` | Le résultat d'une ronde : ce qui a été vu, ce qui a été fait |
| `[alarme]` | La prochaine ronde est programmée (ou la chaîne a été réparée) |
| `[camaps]` | CamAPS FX a été relancé, ou n'a pas pu l'être |
| `[notif]` | Accès aux notifications accordé ou perdu |
| `[shell]` | root / Shizuku : disponible ou non |
| `[boot]` | Le téléphone a redémarré, ou l'application a été mise à jour |
| `[système]` | Changement d'heure, mise en veille |
| `[événement]` | Branchement du chargeur, etc. |

Vous pouvez aussi récupérer ce journal dans le fichier `files/journal.txt` de l'application,
accessible par `adb` ou par une sauvegarde.

## 12. Questions fréquentes

**« CamAPS FX introuvable »**
Le nom de paquet enregistré ne correspond pas. Touchez **Détecter CamAPS FX** : l'application
retrouve le bon nom et l'enregistre. (Sur beaucoup de téléphones, c'est
`com.camdiab.fx_alert.mgdl`.) L'application corrige aussi cette liste toute seule au démarrage.

**« CamAPS FX : Indéterminé »**
Elle ne peut pas savoir si CamAPS tourne. Accordez l'**accès aux notifications** (le plus
efficace) et, à défaut, l'**accès aux stats d'usage**. Tant que l'état est indéterminé, elle ne
relance rien : c'est volontaire.

**Une alerte « Le Bluetooth est éteint » apparaît**
Android 13 et plus interdit à une application ordinaire de rallumer le Bluetooth. Touchez
l'alerte : le Bluetooth se rallume. Pour que ce soit automatique, il faut **root** ou
**Shizuku** — voir [§19](#19-contrôler-le-bluetooth-en-couches).

**« 0 connecté(s) » alors que le capteur fonctionne**
Le capteur n'est pas connecté *à cet instant*. Cela arrive pendant une reconnexion. Ce qui
compte, c'est la tendance : si le compteur reste à zéro pendant des heures, le problème vient du
capteur ou du transmetteur, pas de l'application. Au bout de six rondes, elle vous le signale.

**La surveillance s'arrête après un redémarrage**
Un réglage constructeur bloque le démarrage automatique. Voir [§8](#8-les-réglages-constructeur-souvent-oubliés).

**Elle consomme de la batterie**
Une ronde toutes les 10 minutes coûte très peu. Si vous constatez une consommation anormale,
passez à 15 ou 30 minutes. Une batterie vide est un problème plus grave qu'une ronde en moins.

**Est-ce que ça marche sur mon téléphone ?**
Voir [§22](#22-ce-qui-est-vérifié-ce-qui-ne-lest-pas) : une seule marque a été testée
réellement. Les mécanismes utilisés sont standard, mais chaque surcouche constructeur a ses
surprises.

**Est-ce que je peux l'installer sur le téléphone qui porte la boucle fermée ?**
Oui — c'est même son usage prévu. Mais lisez [§5](#5-ce-quil-ne-fait-pas) d'abord, et gardez les
alarmes de CamAPS FX.

## 13. Vie privée

- **Aucune connexion Internet.** L'application ne demande même pas la permission réseau.
- **Aucune donnée envoyée nulle part.** Pas de compte, pas de télémétrie, pas de publicité.
- **Tout reste sur le téléphone** : les réglages dans les préférences Android, le journal dans un
  fichier local.
- **Aucun accès à vos glycémies.** L'application ne lit ni l'historique de CamAPS FX, ni les
  données du capteur : elle se contente de vérifier que le Bluetooth est allumé, qu'un appareil
  est connecté, et que CamAPS FX a une notification à l'écran.

Le code est public : vous pouvez vérifier tout cela vous-même.

## 14. Désinstallation

1. Désactivez d'abord la surveillance (interrupteur **Surveillance active**). Cela annule les
   alarmes et la tâche planifiée.
2. Désinstallez l'application normalement, depuis les réglages Android.
3. Si vous aviez accordé l'accès aux notifications, il disparaît avec l'application.

**Ne désinstallez jamais CamAPS FX** pour « faire le ménage » : il contient l'historique de votre
boucle et sa configuration. CamAPS Guardian ne touche jamais à ses données.

---

# Partie 2 — pour les développeurs

## 15. Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Réveils                                                     │
│  AlarmManager.setAlarmClock()  ──┐                           │
│  Alarme de secours (+3 min)    ──┼──►  WakeReceiver          │
│  JobScheduler persistant 15 min ─┤        │                  │
│  Battement interne 60 s        ──┘        ▼                  │
│                                    WatchdogService (FGS)     │
│                                          │                   │
│                         ┌────────────────┼───────────────┐   │
│                         ▼                ▼               ▼   │
│                   BluetoothCtl      CamapsCtl        Sys     │
│                         │                │               │   │
│                         ▼                ▼               ▼   │
│                    état, appareils,  présence,      batterie, │
│                    rallumage, cycle   relance        veille   │
│                         │                │               │   │
│                         └────────────────┴───────────────┘   │
│                                          ▼                   │
│                                    Watchdog.check()          │
│                                          ▼                   │
│                             préférences + journal + notif.   │
└──────────────────────────────────────────────────────────────┘
```

Le service ne fait presque rien lui-même : il déclenche `Watchdog.check()`, qui prend toutes les
décisions et écrit tout. L'interface lit un `Snap` (photographie de l'état) exposé par un
`StateFlow` — elle ne pilote jamais la surveillance.

## 16. La stratégie de survie

Aucun de ces mécanismes ne suffit seul ; c'est leur empilement qui tient.

| Mécanisme | Rôle |
|---|---|
| Service de premier plan `connectedDevice\|specialUse` | Pas de limite de durée (contrairement à `dataSync` sur Android 15), notification permanente |
| `START_STICKY` + `stopWithTask="false"` | Survit au balayage depuis les récentes |
| `AlarmManager.setAlarmClock()` | **Traverse Doze** sans autorisation spéciale ; c'est la seule alarme dans ce cas |
| Alarme de secours à +3 min | Si la première est perdue |
| `JobScheduler` persistant (15 min) | Survit au redémarrage, rattrape les trous |
| Battement interne de 60 s | Détecte une chaîne d'alarmes cassée et la réarme |
| Réarmement dans `onDestroy` / `onTaskRemoved` | Replanifie une ronde une minute plus tard |
| `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET` | Réarme après redémarrage, mise à jour, changement d'heure |
| `PARTIAL_WAKE_LOCK` pendant la ronde | Le processeur ne se rendort pas au milieu du contrôle |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Empêche l'optimisation agressive du constructeur |

Le détail du dimensionnement des alarmes est dans `core/Alarms.kt` : alarme principale
(`setAlarmClock`), repli `setExactAndAllowWhileIdle`, puis `setAndAllowWhileIdle`, et enfin un
`JobScheduler` persistant. Chaque échec est journalisé — on sait toujours quelle stratégie a
fonctionné.

## 17. Pourquoi `targetSdk 32`

`BluetoothAdapter.enable()` est déprécié et restreint : à partir d'Android 13 (API 33), une
application qui **cible** 33 ou plus ne peut plus activer le Bluetooth par programme. En ciblant
**32**, l'appel reste autorisé et Android n'affiche qu'un avertissement de compatibilité.

Conséquences assumées :

- L'application ne peut pas être publiée sur le Play Store (ce n'est pas le but).
- Android peut afficher « Application conçue pour une version plus ancienne ».
- **Ne relevez pas `targetSdk` sans accepter de perdre cette possibilité.**

Sur Android 15, en pratique, le refus est fréquent même en ciblant 32 : les replis
(root, Shizuku, alerte) ne sont pas décoratifs, ce sont souvent les seuls chemins qui marchent.

## 18. Détecter CamAPS FX sans root

Trois signaux indépendants, du plus fiable au plus faible :

1. **La notification permanente de CamAPS FX** (`NotificationListenerService.getActiveNotifications()`).
   C'est le meilleur indicateur : une application vivante qui suit un capteur publie une
   notification « en cours ».
2. **L'historique des arrêts du système** (`getHistoricalProcessExitReasons()`, API 30+) : dit si
   CamAPS FX a planté, a été tué pour mémoire, ou a déclenché un ANR. La fenêtre est limitée à
   **20 minutes** : au-delà, un arrêt ancien n'a plus de sens.
3. **Le dernier passage au premier plan** (`UsageStatsManager.queryEvents()` +
   `ACTIVITY_RESUMED`) : utile seulement si l'accès aux stats d'usage est accordé.

Si aucun signal n'est disponible, l'état est **`UNKNOWN`** et aucune relance n'est tentée : mieux
vaut ne rien faire que ramener au premier plan une application qui tournait peut-être très bien.

La relance se fait par l'**intent de lancement** (`NEW_TASK | RESET_TASK_IF_NEEDED |
REORDER_TO_FRONT`), avec un repli `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`.
**Jamais de `force-stop`, jamais de `pm clear`** : ce serait détruire la boucle pour la « réparer ».

## 19. Contrôler le Bluetooth en couches

```
1. BluetoothAdapter.enable()                  → direct, si le système l'autorise
2. su -c "cmd bluetooth_manager enable"       → root
3. su -c "svc bluetooth enable"               → root, autre chemin
4. Shizuku.newProcess(...)                    → root délégué, sans root système
5. ActionActivity (plein écran)               → l'utilisateur touche, ça se rallume
```

Shizuku est appelé **par réflexion** (`rikka.shizuku.Shizuku`) : aucune dépendance, aucune
compilation croisée. Si Shizuku n'est pas installé, l'appel échoue proprement et on passe à
l'étape suivante.

L'alerte plein écran utilise `USE_FULL_SCREEN_INTENT` et `SYSTEM_ALERT_WINDOW`, ce qui permet
aussi de démarrer une activité depuis l'arrière-plan (`Background activity start ... allowed
because SYSTEM_ALERT_WINDOW permission is granted`, visible dans logcat).

## 20. Compiler

Prérequis : **JDK 17** (ou plus récent pour lancer Gradle) et le **SDK Android** avec
`compileSdk 36`.

```bash
git clone https://github.com/Najah69/camaps-guardian.git
cd camaps-guardian
echo "sdk.dir=/chemin/vers/Android/Sdk" > local.properties   # ou définir ANDROID_HOME

./gradlew :app:assembleRelease        # Linux / macOS
gradlew.bat :app:assembleRelease      # Windows
```

L'APK est écrit dans `app/build/outputs/apk/release/app-release.apk` (environ 7,6 Mo).

### Signature

Le dépôt **ne contient aucune clé**. Sans `keystore.properties`, Gradle signe avec la clé de
débogage — l'APK s'installe, mais il ne pourra pas remplacer une version déjà signée avec une
autre clé. Pour signer avec votre propre clé :

```bash
keytool -genkeypair -v -keystore guardian.jks -keyalg RSA -keysize 4096 \
        -validity 10000 -alias guardian
```

Puis créez `keystore.properties` à la racine :

```properties
storeFile=guardian.jks
storePassword=...
keyAlias=guardian
keyPassword=...
```

`guardian.jks` et `keystore.properties` sont dans le `.gitignore` : **ne les publiez jamais**.
Une clé de signature perdue ou divulguée n'est pas récupérable.

## 21. Carte du code

```
app/src/main/java/com/najah/camapsguardian/
├── GuardianApp.kt           Initialisation (canaux, préférences)
├── ActionActivity.kt        Écran d'alerte plein écran (Bluetooth, permissions)
├── core/
│   ├── Prefs.kt             Réglages persistants + StateFlow
│   ├── Fmt.kt               Formatage des heures et des durées
│   ├── Logbook.kt           Journal (600 lignes, rotation automatique)
│   ├── Sys.kt               Batterie, Doze, autorisations spéciales
│   ├── Shell.kt             root / Shizuku (réflexion, sans dépendance)
│   ├── BluetoothCtl.kt      État, appareils connectés, rallumage, cycle
│   ├── CamapsCtl.kt         Présence de CamAPS FX, historique des arrêts, relance
│   ├── PresenceListener.kt  NotificationListenerService
│   ├── Notifier.kt          Canaux, notification permanente, alertes plein écran
│   ├── Alarms.kt            Chaîne d'alarmes (alarmClock + secours + JobScheduler)
│   ├── Watchdog.kt          La ronde : décisions et réparations
│   ├── WatchdogService.kt   Service de premier plan, battement de cœur
│   ├── WakeJobService.kt    Tâche périodique persistante
│   ├── Receivers.kt         Réveil, démarrage, changement d'heure
│   ├── Control.kt           Actions de haut niveau (activer, intervalle, vérifier)
│   └── Status.kt            Photographie de l'état pour l'interface
└── ui/                      MainActivity, écran Compose, thème
```

Pile technique : Kotlin 2.3, Jetpack Compose (BOM 2026.03.01), AGP 9.0.1, Gradle 9.1,
`minSdk 26`, `compileSdk 36`, `targetSdk 32`, `jvmToolchain(17)`.

## 22. Ce qui est vérifié, ce qui ne l'est pas

**Vérifié sur un appareil réel** — Hammer Iron 6 5G, Android 15 (API 35), sans root ni Shizuku :

- [x] Service de premier plan actif (`isForeground=true`, `types=0x40000010`)
- [x] Alarmes `RTC_WAKEUP` exactes avec `showIntent` (`setAlarmClock`, `exactAllowReason=permission`)
- [x] Tâche `JobScheduler` persistante enregistrée
- [x] Ronde manuelle : détection du Bluetooth, des appareils connectés et de CamAPS FX
- [x] Détection de CamAPS FX par sa notification permanente
- [x] Balayage depuis les récentes : le service survit et replanifie une ronde
- [x] **Redémarrage complet** : `BOOT_COMPLETED` → service démarré → CamAPS FX relancé → alarme reprogrammée
- [x] Repli par alerte plein écran quand le Bluetooth ne peut pas être rallumé
- [x] Absence de relance intempestive quand CamAPS FX est déjà vivant

**Non vérifié, et signalé comme tel** :

- [ ] Autres fabricants (Xiaomi, Samsung, Huawei, Oppo, Pixel…) — mécanismes standard, mais
      surcouches différentes
- [ ] Rallumage du Bluetooth par root ou Shizuku (aucun de ces accès n'existait sur le téléphone de test)
- [ ] Comportement sur plusieurs semaines (fuite mémoire, dérive de la batterie)
- [ ] Mode Doze profond sur une nuit complète
- [ ] Android 8 à 14 (seul Android 15 a été utilisé)

Si vous testez sur un autre appareil, un retour dans les *Issues* a de la valeur — surtout s'il
est négatif.

## 23. Contribuer

Les retours sont bienvenus, en particulier :

- un rapport de test sur un autre fabricant (modèle, version d'Android, ce qui a marché) ;
- un journal (`[ronde]`, `[alarme]`) montrant un cas où la surveillance n'a pas repris ;
- une correction de traduction ou de formulation, si un passage n'est pas clair.

Merci de **ne pas joindre** de capture contenant vos glycémies, votre nom, ou des données de
santé : un extrait du journal de CamAPS Guardian suffit toujours.

## 24. Licence

MIT — voir [LICENSE](LICENSE). Faites-en ce que vous voulez, y compris commercialement, sans
garantie d'aucune sorte.

---

## English summary

**CamAPS Guardian** is a small Android app for people with type 1 diabetes using **CamAPS FX**.
Every 5, 10, 15 or 30 minutes it checks whether Bluetooth is on, whether a device is actually
connected, and whether CamAPS FX is still running — then tries to restore what is broken
(turn Bluetooth back on, bring CamAPS FX back to the foreground, re-arm its own wake-up chain).

It is **not a medical device**: it does not dose insulin, does not read glucose, and does not
replace CamAPS FX or its alarms — keep those enabled. It never force-stops or clears data from
CamAPS FX. No internet permission, no telemetry, no account.

Requires Android 8.0+. Works fully without root; root or Shizuku additionally allows silent
Bluetooth re-enabling on Android 13+. Signed APK is published in Releases; build with
`./gradlew :app:assembleRelease`. MIT licensed. Not affiliated with, endorsed by, or supported
by CamDiab Ltd.

---

*CamAPS FX est une marque de CamDiab Ltd. Ce projet est indépendant : il n'est ni édité, ni
validé, ni soutenu par CamDiab Ltd.*
