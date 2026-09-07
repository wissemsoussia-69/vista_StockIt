# StockIT - Analyse de migration vers Kotlin Multiplatform (Android + iOS)

Date: 2026-09-07  
Branche de travail: ios-migration

## 1. Objectif et contrainte

Objectif: evoluer le meme repository vers une architecture multiplateforme sans casser le comportement Android actuel.

Contrainte majeure: le code partage KMP doit etre en Kotlin. Le projet applicatif actuel est majoritairement en Java Android, donc la migration vers `shared` doit etre progressive et precedee d'une conversion ciblee Java -> Kotlin sur le domaine metier.

## 2. Etat actuel du projet

Structure actuelle (mono-module Android):
- Module unique: `:app`
- Sources principales: `app/src/main/java/com/example/stockit`
- Repartition approximative:
  - `model`: 32 fichiers
  - `controller`: 20 fichiers
  - `util`: 16 fichiers
  - activities/top-level UI: 16 fichiers

Technologies et dependances cle:
- UI Android classique (Activities + RecyclerView adapters)
- Room (`AppDatabase`, `*Dao`, entites annotees)
- CameraX + ML Kit
- Retrofit + OkHttp
- Auth0 Android SDK
- Firebase Messaging
- Jira Cloud REST (lectures et creations)
- IA (Gemini/Cimpress/Portkey/Hugging Face) via utilitaires reseau

## 3. Principes de decomposition KMP

### 3.1 Regle de base
Tout ce qui depend de `android.*`, `androidx.*` Android-only, CameraX, ML Kit, Auth0 Android SDK, Firebase Android reste hors `shared`.

### 3.2 Cibles futures
- `shared`: modeles metier, use-cases, logique de matching/validation, clients API abstraits, sync offline abstrait
- `androidApp`: UI Android, camera, stockage local Android concret, notifications Android, Auth0 Android concret
- `iosApp`: UI iOS, camera iOS, persistance iOS concrete, notifications iOS, auth iOS concrete

## 4. Inventaire par zone de portabilite

## 4.1 Android-specific (reste Android)

UI / Presentation Android:
- Activities (`MainActivity`, `ScanAssetActivity`, `ScanOutActivity`, etc.)
- Adapters (`controller/*Adapter`)
- Widget Android (`StockWidget`)

Plateforme Android:
- [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml)
- [app/src/main/java/com/example/stockit/MyFirebaseMessagingService.java](app/src/main/java/com/example/stockit/MyFirebaseMessagingService.java)
- [app/src/main/java/com/example/stockit/util/SessionManager.java](app/src/main/java/com/example/stockit/util/SessionManager.java) (SharedPreferences)
- [app/src/main/java/com/example/stockit/util/Auth0Manager.java](app/src/main/java/com/example/stockit/util/Auth0Manager.java) (SDK Auth0 Android)
- [app/src/main/java/com/example/stockit/model/AppDatabase.java](app/src/main/java/com/example/stockit/model/AppDatabase.java) (Room)

Vision / Camera:
- CameraX, ML Kit, overlays, interactions haptiques

## 4.2 Candidats Shared (apres refactor et conversion Kotlin)

Ces classes sont techniquement de la logique metier/reseau, mais aujourd'hui melees a des callbacks/threading Android ou a `BuildConfig` Android.

Integrations Jira:
- [app/src/main/java/com/example/stockit/util/JiraClient.java](app/src/main/java/com/example/stockit/util/JiraClient.java)
- [app/src/main/java/com/example/stockit/util/JiraReader.java](app/src/main/java/com/example/stockit/util/JiraReader.java)

Logique IA d'affectation:
- [app/src/main/java/com/example/stockit/util/TicketMatcher.java](app/src/main/java/com/example/stockit/util/TicketMatcher.java)

IA vision (partie pure logique/prompt):
- [app/src/main/java/com/example/stockit/util/GeminiGatewayClient.java](app/src/main/java/com/example/stockit/util/GeminiGatewayClient.java)

Modele metier (a decoupler des annotations Room):
- Classes metier de `model` (Product, Ticket, mouvements, etc.)
- Creer des modeles de domaine purs dans `shared` puis mapper vers Room Android

## 4.3 A implementer cote iOS (equivalents natifs)

- UI (SwiftUI ou UIKit)
- Camera + capture image iOS
- Persistance locale (SQLDelight ou CoreData via couche abstraction)
- Notifications iOS
- Session locale iOS (equivalent SessionManager)
- Auth0 iOS (SDK iOS)

## 5. Couplages bloquants identifies avant migration

1. Java dans toute la logique metier
- `shared` KMP exige Kotlin
- Action prealable: convertir progressivement les couches metier cibles en Kotlin

2. MainController tres central et Android-couple
- [app/src/main/java/com/example/stockit/controller/MainController.java](app/src/main/java/com/example/stockit/controller/MainController.java)
- Melange UI callbacks, DB, API, auth, IA
- Action: extraire des use-cases metier sans dependance Android

3. BuildConfig/Resources dans logique
- Certains utilitaires lisent directement BuildConfig/strings Android
- Action: injecter la configuration via interfaces multiplateformes

4. Room annote dans les modeles
- Action: separer domaine (shared) et persistence Android (Room entities + mappers)

## 6. Architecture cible recommandee (progressive)

Proposition de structure dans le meme repository:

- `shared/`
  - `src/commonMain/kotlin/...`
    - domain models
    - use cases
    - repositories interfaces
    - api clients abstraits
    - validation rules
    - sync policy abstraite
  - `src/androidMain/kotlin/...`
    - impl Android des interfaces (storage, network wiring specifique si besoin)
  - `src/iosMain/kotlin/...`
    - impl iOS des interfaces

- `androidApp/`
  - UI Android et integration framework Android

- `iosApp/`
  - shell iOS et UI

## 7. Plan de migration recommande (sans casser Android)

Phase 0 - Stabilisation (aucun changement fonctionnel)
- Conserver `:app` tel quel
- Ajouter tests unitaires autour des regles metier critiques

Phase 1 - Preparation KMP (sans deplacer de logique)
- Ajouter module `shared` minimal vide
- Configurer Gradle KMP
- Ajouter pipeline build Android inchangee

Phase 2 - Extraction domaine en Kotlin
- Creer modeles de domaine partages en Kotlin
- Ajouter mappers Android <-> domaine
- Extraire use-cases purs depuis `MainController`

Phase 3 - Extraction reseau partage
- Migrer Jira/TicketMatcher vers `shared` avec Ktor/serialization
- Injecter configuration (clés, URL) via interfaces

Phase 4 - Extraction offline/sync
- Definir interfaces repository + sync dans `shared`
- Conserver impl Android en premier, puis ajouter impl iOS

Phase 5 - Creation iosApp
- Ajouter application iOS (UI minimale)
- Consommer use-cases `shared`
- Parite progressive par fonctionnalite

## 8. Backlog de migration priorise (prochaines taches)

1. Initialiser module `shared` KMP (vide) sans activer iOS UI
2. Definir 5 modeles domaine cibles en Kotlin (Product, Ticket, Assignment, Movement, UserSession)
3. Extraire un premier use-case pur: Ticket assignment rules (actuellement TicketMatcher)
4. Introduire un provider de configuration multiplateforme (API keys/endpoints)
5. Brancher Android sur ces abstractions sans changer les ecrans

## 9. Risques et mitigation

Risque: regression Android pendant extraction
- Mitigation: facade de compatibilite + tests non-regression avant/apres extraction

Risque: duplication logique pendant transition
- Mitigation: migration par tranches avec source-of-truth unique (use-cases shared)

Risque: migration trop large d'un coup
- Mitigation: vertical slices (une fonctionnalite a la fois)

## 10. Conclusion

Le choix Kotlin Multiplatform est pertinent pour StockIT, mais la trajectoire doit etre incrementalement pilotee:
- pas de fork Android/iOS separes,
- pas de migration big-bang,
- extraction progressive de la logique metier vers `shared` Kotlin,
- maintien du comportement Android comme contrainte non-negociable.
