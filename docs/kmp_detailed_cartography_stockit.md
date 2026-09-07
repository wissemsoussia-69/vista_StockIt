# StockIT - Cartographie technique detaillee pour migration KMP

Date: 2026-09-07
Branche: ios-migration
Contraintes respectees: aucune modification de code applicatif Android, aucun module KMP cree, aucune classe deplacee.

## Portee analysee
- 84 classes Java analysees sous app/src/main/java/com/example/stockit
- Manifest, dependances Gradle, couches UI, data, API, auth, camera, IA, notifications

## Legende de classification
- A. DIRECTLY SHAREABLE: partageable en commun avec adaptation minimale.
- B. SHARED AFTER REFACTORING: partageable apres extraction de dependances Android.
- C. ANDROID-ONLY: reste specifique Android.
- D. IOS-ONLY: composant cible iOS a creer (absent du code actuel).
- E. COMMON INTERFACE / PLATFORM IMPLEMENTATION: contrat commun + implementations natives par plateforme.

## 1) Modeles et logique metier
- Modeles actuels fortement couples a Room via annotations dans package model.
- Recommandation: creer des modeles domaine Kotlin purs dans shared/domain, puis mappers Android vers entites Room.
- Candidats prioritaires: Product, PurchaseOrder, ShippingOrder, StockMovement, JiraTicket, AnalyzedTicket, User.

## 2) Jira Assets
- La logique Jira est actuellement dans utilitaires Android-couples (callbacks/threading + BuildConfig).
- Recommandation: interface commune shared/network + shared/repositories, implementation HTTP Ktor multiplateforme.
- Conserver temporairement les clients Android existants comme reference de comportement pendant la transition.

## 3) Jira Software
- Lecture/recherche tickets et attribution IA via JiraReader/JiraClient/TicketMatcher.
- Partage possible apres extraction des DTO + parser + policy de retry/timeout en Kotlin commun.

## 4) Offline et synchronisation
- Offline actuel base sur Room/DAO dans model + orchestration MainController/Workers.
- Cible: contrats repository dans shared; implementations plateforme (Room Android, SQLDelight/CoreData iOS).
- Le mecanisme pending/retry doit etre modele en policy partagee (shared/sync) avec persistance native par plateforme.

## 5) Auth0 / Okta
- Auth0Manager et AuthBearerInterceptor sont nativement Android (SDK Auth0 Android + SharedPreferences/Keystore).
- Cible: interface auth/session commune en shared/auth, impl Android et iOS separees, flux OIDC+PKCE conserve.
- Connection enterprise vista-okta doit rester configurable via provider-specific params plateforme.

## 6) Camera / Barcode / OCR
- CameraX/ML Kit/OCR restent Android-only.
- Cible: interface metier shared pour demande de capture et resultat normalise; impl native Android/iOS separees.

## 7) IA
- GeminiGatewayClient + TicketMatcher + parsers peuvent etre partages apres refactor Kotlin.
- A extraire: schemas requetes/reponses, parse JSON, validation, policy timeout/retry, mapping erreurs.

## 8) Chaine Stock Entry (cartographie)
- PO -> scan produit -> scan facture -> scan carton -> OCR -> IA -> extraction -> validation -> sync Jira Assets
- Android-only: capture camera, UI dialogs, navigation activity
- Shared after refactor: validation metier, normalisation labels, reconciliation rules, policies sync Jira
- iOS-only: UI camera native, flux UX equivalent

## 9) Chaine Stock Exit (cartographie)
- Ticket -> selection/scan -> recherche asset -> verif disponibilite -> association -> decrement -> sync Jira -> notification -> audit
- Android-only: ecrans ScanOut/TicketList + interactions camera
- Shared after refactor: regles d association ticket-asset, policies de statut/disponibilite, orchestration retry
- iOS-only: presentation et interactions natives equivalentes

## 10) UI
- Activities/adapters/layout XML restent Android-only.
- Extraction recommandee: view-model/use-cases Kotlin partages, UI Android et iOS distinctes.

## 11) Notifications
- NotificationHelper, Firebase Messaging et workers sont Android-only.
- Cible: logique de decision de notification en shared; emission native Android/iOS par adaptateurs plateforme.

## 12) Analytics / Reporting
- Evenements et rapports: logique partiellement partageable (schema evenement, regles agregation).
- Emission transport (webhook/n8n, OS scheduler, storage local) a implementer par plateforme.

## Architecture cible proposee
StockIT/
- shared/domain
- shared/data
- shared/network
- shared/repositories
- shared/sync
- shared/validation
- shared/auth
- shared/common
- app (android actuel conserve pendant les premieres phases)
- iosApp (a creer apres extraction progressive)

## Matrice finale des classes actuelles
Colonnes: Composant | Fichier actuel | Package | Role | Android-specifique | Network/API | Room/SQLite | Auth0/Okta | Camera/MLKit/OCR | Jira | n8n/automation | IA/Gateway | Couplage Android | Classification | Shared ? | Android | iOS | Refactoring necessaire | Priorite

| Composant | Fichier actuel | Package | Role | Android-specifique | Network/API | Room/SQLite | Auth0/Okta | Camera/MLKit/OCR | Jira | n8n/automation | IA/Gateway | Couplage Android | Classification | Shared ? | Android | iOS | Refactoring necessaire | Priorite |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| AIChatActivity | app/src/main/java/com/example/stockit/AIChatActivity.java | com.example.stockit | Android screen workflow and user interaction orchestration | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| BatchScanActivity | app/src/main/java/com/example/stockit/BatchScanActivity.java | com.example.stockit | Android screen workflow and user interaction orchestration | True | False | False | False | True | False | False | False | High | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| ChatActivity | app/src/main/java/com/example/stockit/ChatActivity.java | com.example.stockit | Android screen workflow and user interaction orchestration | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| ClaimActivity | app/src/main/java/com/example/stockit/ClaimActivity.java | com.example.stockit | Android screen workflow and user interaction orchestration | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| LoginActivity | app/src/main/java/com/example/stockit/LoginActivity.java | com.example.stockit | Android screen workflow and user interaction orchestration | True | False | False | True | False | False | False | False | High | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| MainActivity | app/src/main/java/com/example/stockit/MainActivity.java | com.example.stockit | Android screen workflow and user interaction orchestration | True | False | False | True | False | False | True | True | High | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| MyFirebaseMessagingService | app/src/main/java/com/example/stockit/MyFirebaseMessagingService.java | com.example.stockit | Utility or orchestration helper | True | False | False | False | False | False | True | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P2 |
| PackageLabelActivity | app/src/main/java/com/example/stockit/PackageLabelActivity.java | com.example.stockit | Android screen workflow and user interaction orchestration | True | False | False | False | True | False | False | False | High | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| POSelectionActivity | app/src/main/java/com/example/stockit/POSelectionActivity.java | com.example.stockit | Android screen workflow and user interaction orchestration | True | False | False | False | True | False | False | True | High | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| ProfileActivity | app/src/main/java/com/example/stockit/ProfileActivity.java | com.example.stockit | Android screen workflow and user interaction orchestration | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| ReceivePackageActivity | app/src/main/java/com/example/stockit/ReceivePackageActivity.java | com.example.stockit | Android screen workflow and user interaction orchestration | True | False | False | False | True | True | True | False | High | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| ScanAssetActivity | app/src/main/java/com/example/stockit/ScanAssetActivity.java | com.example.stockit | Android screen workflow and user interaction orchestration | True | False | False | False | True | True | True | True | High | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| ScanOutActivity | app/src/main/java/com/example/stockit/ScanOutActivity.java | com.example.stockit | Android screen workflow and user interaction orchestration | True | False | False | False | True | True | True | True | High | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| SplashActivity | app/src/main/java/com/example/stockit/SplashActivity.java | com.example.stockit | Android screen workflow and user interaction orchestration | True | False | False | True | False | False | False | False | High | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| StockWidget | app/src/main/java/com/example/stockit/StockWidget.java | com.example.stockit | Utility or orchestration helper | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P2 |
| TicketListActivity | app/src/main/java/com/example/stockit/TicketListActivity.java | com.example.stockit | Android screen workflow and user interaction orchestration | True | False | False | True | False | True | False | False | High | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| AIInsightAdapter | app/src/main/java/com/example/stockit/controller/AIInsightAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| ArticleAdapter | app/src/main/java/com/example/stockit/controller/ArticleAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| AssetTicketCoordinator | app/src/main/java/com/example/stockit/controller/AssetTicketCoordinator.java | com.example.stockit.controller | Utility or orchestration helper | True | False | False | False | False | False | True | False | Medium | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Convert Java logic to Kotlin and remove direct android.* dependencies | P2 |
| AuditLogAdapter | app/src/main/java/com/example/stockit/controller/AuditLogAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| CategoryAdapter | app/src/main/java/com/example/stockit/controller/CategoryAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| ChatAdapter | app/src/main/java/com/example/stockit/controller/ChatAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| ClaimAdapter | app/src/main/java/com/example/stockit/controller/ClaimAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| MainController | app/src/main/java/com/example/stockit/controller/MainController.java | com.example.stockit.controller | Application orchestrator combining data, workflows, and integrations | True | True | False | True | False | True | True | True | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Split into Kotlin use-cases + repository interfaces; remove Android Handler/callback coupling | P0 |
| NotificationHelper | app/src/main/java/com/example/stockit/controller/NotificationHelper.java | com.example.stockit.controller | Utility or orchestration helper | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P2 |
| POBlockAdapter | app/src/main/java/com/example/stockit/controller/POBlockAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| ProductAdapter | app/src/main/java/com/example/stockit/controller/ProductAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| ProfileAdapter | app/src/main/java/com/example/stockit/controller/ProfileAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| PurchaseOrderAdapter | app/src/main/java/com/example/stockit/controller/PurchaseOrderAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| ReportWorker | app/src/main/java/com/example/stockit/controller/ReportWorker.java | com.example.stockit.controller | Utility or orchestration helper | True | False | False | False | False | False | True | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P2 |
| ShippingOrderAdapter | app/src/main/java/com/example/stockit/controller/ShippingOrderAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | True | False | False | False | True | False | False | High | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| StockMovementAdapter | app/src/main/java/com/example/stockit/controller/StockMovementAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| SupplierAdapter | app/src/main/java/com/example/stockit/controller/SupplierAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| TicketAdapter | app/src/main/java/com/example/stockit/controller/TicketAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | False | False | False | False | True | False | False | High | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| TimelineAdapter | app/src/main/java/com/example/stockit/controller/TimelineAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| UserAdapter | app/src/main/java/com/example/stockit/controller/UserAdapter.java | com.example.stockit.controller | Android UI list binding/presentation component | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P3 |
| AIInsight | app/src/main/java/com/example/stockit/model/AIInsight.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | False | False | False | False | False | False | False | False | Low | A. DIRECTLY SHAREABLE | Yes | Consumer of shared | Consumer of shared | No structural change required beyond Kotlin conversion and packaging in shared | P1 |
| AIService | app/src/main/java/com/example/stockit/model/AIService.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | True | True | False | False | False | False | False | True | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Move HTTP contracts to shared Ktor; inject base URLs and secrets via platform config | P1 |
| AnalyzedTicket | app/src/main/java/com/example/stockit/model/AnalyzedTicket.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | True | False | True | False | False | True | False | True | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Create pure Kotlin domain models; map to Room entities in Android data layer | P1 |
| AnalyzedTicketDao | app/src/main/java/com/example/stockit/model/AnalyzedTicketDao.java | com.example.stockit.model | Persistence access layer for local data | True | False | True | False | False | False | False | False | High | E. COMMON INTERFACE / PLATFORM IMPLEMENTATION | Interface only | Platform implementation | Platform implementation | Define repository contracts in shared; keep Room Android impl; add SQLDelight/CoreData iOS impl | P1 |
| ApiService | app/src/main/java/com/example/stockit/model/ApiService.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | True | True | False | False | False | False | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Move HTTP contracts to shared Ktor; inject base URLs and secrets via platform config | P1 |
| AppDatabase | app/src/main/java/com/example/stockit/model/AppDatabase.java | com.example.stockit.model | Room database configuration and DAO registry | True | False | True | False | False | False | False | False | High | E. COMMON INTERFACE / PLATFORM IMPLEMENTATION | Interface only | Platform implementation | Platform implementation | Define repository contracts in shared; keep Room Android impl; add SQLDelight/CoreData iOS impl | P1 |
| AuditLog | app/src/main/java/com/example/stockit/model/AuditLog.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | True | False | True | False | False | False | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Create pure Kotlin domain models; map to Room entities in Android data layer | P1 |
| AuditLogDao | app/src/main/java/com/example/stockit/model/AuditLogDao.java | com.example.stockit.model | Persistence access layer for local data | True | False | True | False | False | False | False | False | High | E. COMMON INTERFACE / PLATFORM IMPLEMENTATION | Interface only | Platform implementation | Platform implementation | Define repository contracts in shared; keep Room Android impl; add SQLDelight/CoreData iOS impl | P1 |
| Category | app/src/main/java/com/example/stockit/model/Category.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | True | False | True | False | False | False | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Create pure Kotlin domain models; map to Room entities in Android data layer | P1 |
| CategoryDao | app/src/main/java/com/example/stockit/model/CategoryDao.java | com.example.stockit.model | Persistence access layer for local data | True | False | True | False | False | False | False | False | High | E. COMMON INTERFACE / PLATFORM IMPLEMENTATION | Interface only | Platform implementation | Platform implementation | Define repository contracts in shared; keep Room Android impl; add SQLDelight/CoreData iOS impl | P1 |
| ChatMessage | app/src/main/java/com/example/stockit/model/ChatMessage.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | False | False | False | False | False | False | False | False | Low | A. DIRECTLY SHAREABLE | Yes | Consumer of shared | Consumer of shared | No structural change required beyond Kotlin conversion and packaging in shared | P1 |
| Claim | app/src/main/java/com/example/stockit/model/Claim.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | True | False | True | False | False | False | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Create pure Kotlin domain models; map to Room entities in Android data layer | P1 |
| ClaimDao | app/src/main/java/com/example/stockit/model/ClaimDao.java | com.example.stockit.model | Persistence access layer for local data | True | False | True | False | False | False | False | False | High | E. COMMON INTERFACE / PLATFORM IMPLEMENTATION | Interface only | Platform implementation | Platform implementation | Define repository contracts in shared; keep Room Android impl; add SQLDelight/CoreData iOS impl | P1 |
| HFVisionResponse | app/src/main/java/com/example/stockit/model/HFVisionResponse.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | False | False | False | False | False | False | False | False | Low | A. DIRECTLY SHAREABLE | Yes | Consumer of shared | Consumer of shared | No structural change required beyond Kotlin conversion and packaging in shared | P1 |
| HuggingFaceApiService | app/src/main/java/com/example/stockit/model/HuggingFaceApiService.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | True | True | False | False | False | False | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Move HTTP contracts to shared Ktor; inject base URLs and secrets via platform config | P1 |
| JiraTicket | app/src/main/java/com/example/stockit/model/JiraTicket.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | False | False | False | False | False | True | False | False | Low | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Create pure Kotlin domain models; map to Room entities in Android data layer | P1 |
| Product | app/src/main/java/com/example/stockit/model/Product.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | True | False | True | False | False | False | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Create pure Kotlin domain models; map to Room entities in Android data layer | P1 |
| ProductDao | app/src/main/java/com/example/stockit/model/ProductDao.java | com.example.stockit.model | Persistence access layer for local data | True | False | True | False | False | False | False | False | High | E. COMMON INTERFACE / PLATFORM IMPLEMENTATION | Interface only | Platform implementation | Platform implementation | Define repository contracts in shared; keep Room Android impl; add SQLDelight/CoreData iOS impl | P1 |
| PurchaseOrder | app/src/main/java/com/example/stockit/model/PurchaseOrder.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | True | False | True | False | False | False | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Create pure Kotlin domain models; map to Room entities in Android data layer | P1 |
| PurchaseOrderDao | app/src/main/java/com/example/stockit/model/PurchaseOrderDao.java | com.example.stockit.model | Persistence access layer for local data | True | False | True | False | False | False | False | False | High | E. COMMON INTERFACE / PLATFORM IMPLEMENTATION | Interface only | Platform implementation | Platform implementation | Define repository contracts in shared; keep Room Android impl; add SQLDelight/CoreData iOS impl | P1 |
| Quest | app/src/main/java/com/example/stockit/model/Quest.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | True | False | True | False | False | False | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Create pure Kotlin domain models; map to Room entities in Android data layer | P1 |
| QuestDao | app/src/main/java/com/example/stockit/model/QuestDao.java | com.example.stockit.model | Persistence access layer for local data | True | False | True | False | False | False | False | False | High | E. COMMON INTERFACE / PLATFORM IMPLEMENTATION | Interface only | Platform implementation | Platform implementation | Define repository contracts in shared; keep Room Android impl; add SQLDelight/CoreData iOS impl | P1 |
| ShippingOrder | app/src/main/java/com/example/stockit/model/ShippingOrder.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | True | False | True | False | False | False | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Create pure Kotlin domain models; map to Room entities in Android data layer | P1 |
| ShippingOrderDao | app/src/main/java/com/example/stockit/model/ShippingOrderDao.java | com.example.stockit.model | Persistence access layer for local data | True | False | True | False | False | False | False | False | High | E. COMMON INTERFACE / PLATFORM IMPLEMENTATION | Interface only | Platform implementation | Platform implementation | Define repository contracts in shared; keep Room Android impl; add SQLDelight/CoreData iOS impl | P1 |
| StockMovement | app/src/main/java/com/example/stockit/model/StockMovement.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | True | False | True | False | False | True | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Create pure Kotlin domain models; map to Room entities in Android data layer | P1 |
| StockMovementDao | app/src/main/java/com/example/stockit/model/StockMovementDao.java | com.example.stockit.model | Persistence access layer for local data | True | False | True | False | False | False | False | False | High | E. COMMON INTERFACE / PLATFORM IMPLEMENTATION | Interface only | Platform implementation | Platform implementation | Define repository contracts in shared; keep Room Android impl; add SQLDelight/CoreData iOS impl | P1 |
| Supplier | app/src/main/java/com/example/stockit/model/Supplier.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | True | False | True | False | False | False | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Create pure Kotlin domain models; map to Room entities in Android data layer | P1 |
| SupplierDao | app/src/main/java/com/example/stockit/model/SupplierDao.java | com.example.stockit.model | Persistence access layer for local data | True | False | True | False | False | False | False | False | High | E. COMMON INTERFACE / PLATFORM IMPLEMENTATION | Interface only | Platform implementation | Platform implementation | Define repository contracts in shared; keep Room Android impl; add SQLDelight/CoreData iOS impl | P1 |
| User | app/src/main/java/com/example/stockit/model/User.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | True | False | True | False | False | False | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Create pure Kotlin domain models; map to Room entities in Android data layer | P1 |
| UserDao | app/src/main/java/com/example/stockit/model/UserDao.java | com.example.stockit.model | Persistence access layer for local data | True | False | True | False | False | False | False | False | High | E. COMMON INTERFACE / PLATFORM IMPLEMENTATION | Interface only | Platform implementation | Platform implementation | Define repository contracts in shared; keep Room Android impl; add SQLDelight/CoreData iOS impl | P1 |
| ZycusApiService | app/src/main/java/com/example/stockit/model/ZycusApiService.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | True | True | False | False | False | False | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Move HTTP contracts to shared Ktor; inject base URLs and secrets via platform config | P1 |
| ZycusPR | app/src/main/java/com/example/stockit/model/ZycusPR.java | com.example.stockit.model | Domain/data model used by UI, persistence, and integrations | False | False | False | False | False | False | False | False | Low | A. DIRECTLY SHAREABLE | Yes | Consumer of shared | Consumer of shared | No structural change required beyond Kotlin conversion and packaging in shared | P1 |
| Auth0Manager | app/src/main/java/com/example/stockit/util/Auth0Manager.java | com.example.stockit.util | Authentication/session and token propagation layer | True | True | False | True | False | False | False | False | High | E. COMMON INTERFACE / PLATFORM IMPLEMENTATION | Interface only | Platform implementation | Platform implementation | Extract auth/session interfaces; keep secure token storage native | P1 |
| AuthBearerInterceptor | app/src/main/java/com/example/stockit/util/AuthBearerInterceptor.java | com.example.stockit.util | Authentication/session and token propagation layer | True | True | False | True | False | True | False | True | High | E. COMMON INTERFACE / PLATFORM IMPLEMENTATION | Interface only | Platform implementation | Platform implementation | Extract auth/session interfaces; keep secure token storage native | P1 |
| BarcodeOverlay | app/src/main/java/com/example/stockit/util/BarcodeOverlay.java | com.example.stockit.util | Utility or orchestration helper | True | False | False | False | True | False | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Convert Java logic to Kotlin and remove direct android.* dependencies | P2 |
| DeliveryNoteParser | app/src/main/java/com/example/stockit/util/DeliveryNoteParser.java | com.example.stockit.util | AI/OCR parsing and decision support logic | True | True | False | True | True | False | False | True | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Keep rules/parsing in shared; isolate camera/image and Android threading | P2 |
| GeminiGatewayClient | app/src/main/java/com/example/stockit/util/GeminiGatewayClient.java | com.example.stockit.util | AI/OCR parsing and decision support logic | True | True | False | True | True | False | False | True | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Keep rules/parsing in shared; isolate camera/image and Android threading | P1 |
| ImageOptimizerUtil | app/src/main/java/com/example/stockit/util/ImageOptimizerUtil.java | com.example.stockit.util | Utility or orchestration helper | True | False | False | False | True | False | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Convert Java logic to Kotlin and remove direct android.* dependencies | P2 |
| JiraClient | app/src/main/java/com/example/stockit/util/JiraClient.java | com.example.stockit.util | Jira Software API integration client | True | True | False | False | True | True | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Move HTTP contracts to shared Ktor; inject base URLs and secrets via platform config | P1 |
| JiraReader | app/src/main/java/com/example/stockit/util/JiraReader.java | com.example.stockit.util | Jira Software API integration client | True | True | False | False | False | True | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Move HTTP contracts to shared Ktor; inject base URLs and secrets via platform config | P1 |
| KitAntiOubliDialog | app/src/main/java/com/example/stockit/util/KitAntiOubliDialog.java | com.example.stockit.util | Utility or orchestration helper | True | False | False | False | False | True | False | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Convert Java logic to Kotlin and remove direct android.* dependencies | P2 |
| PackageLabelParser | app/src/main/java/com/example/stockit/util/PackageLabelParser.java | com.example.stockit.util | AI/OCR parsing and decision support logic | True | True | False | True | True | False | False | True | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Keep rules/parsing in shared; isolate camera/image and Android threading | P2 |
| PdfReportGenerator | app/src/main/java/com/example/stockit/util/PdfReportGenerator.java | com.example.stockit.util | Utility or orchestration helper | True | False | False | False | False | False | False | False | Medium | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Convert Java logic to Kotlin and remove direct android.* dependencies | P2 |
| SessionManager | app/src/main/java/com/example/stockit/util/SessionManager.java | com.example.stockit.util | Authentication/session and token propagation layer | True | False | False | True | False | False | False | False | High | E. COMMON INTERFACE / PLATFORM IMPLEMENTATION | Interface only | Platform implementation | Platform implementation | Extract auth/session interfaces; keep secure token storage native | P1 |
| SlackNotifier | app/src/main/java/com/example/stockit/util/SlackNotifier.java | com.example.stockit.util | Automation/notification integration layer | True | True | False | False | False | False | True | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Convert Java logic to Kotlin and remove direct android.* dependencies | P2 |
| StockItReporter | app/src/main/java/com/example/stockit/util/StockItReporter.java | com.example.stockit.util | Automation/notification integration layer | True | True | False | True | True | False | True | False | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Convert Java logic to Kotlin and remove direct android.* dependencies | P2 |
| TicketMatcher | app/src/main/java/com/example/stockit/util/TicketMatcher.java | com.example.stockit.util | AI/OCR parsing and decision support logic | True | True | False | True | False | True | False | True | High | B. SHARED AFTER REFACTORING | Yes after refactor | Consumer of shared | Consumer of shared | Keep rules/parsing in shared; isolate camera/image and Android threading | P1 |
| VistaSnackbar | app/src/main/java/com/example/stockit/util/VistaSnackbar.java | com.example.stockit.util | Utility or orchestration helper | True | False | False | False | False | False | False | False | Medium | C. ANDROID-ONLY | No | Primary implementation | Reimplement UI/feature natively | None for migration planning stage | P2 |

## Composants IOS-ONLY cibles (non presentes dans le code actuel)
| Composant cible iOS | Classification | Raison |
|---|---|---|
| iOSCameraCaptureService | D. IOS-ONLY | Equivalent natif de CameraX capture et preview |
| iOSBarcodeScanner | D. IOS-ONLY | Equivalent natif scan barcode/vision |
| iOSOcrBridge | D. IOS-ONLY | Equivalent OCR/vision iOS |
| iOSPushNotificationHandler | D. IOS-ONLY | Equivalent Firebase Messaging/notification pipeline iOS |
| iOSSecureTokenStore | D. IOS-ONLY | Equivalent Keystore/SharedPreferences securise |
| iOSAppLifecycleSyncTrigger | D. IOS-ONLY | Equivalent WorkManager/background triggers |
| iOSStockEntryScreens | D. IOS-ONLY | UI native iOS pour flux entree stock |
| iOSStockExitScreens | D. IOS-ONLY | UI native iOS pour flux sortie stock |

## SHARED AFTER REFACTORING - extraction precise
1. MainController
- Extraire: use-cases (stock entry, stock exit, matching, reporting, audit rules)
- Supprimer de la logique metier: Handler/Looper, Context, callbacks UI Android
- Interfaces a creer: StockRepository, TicketRepository, AssetRepository, NotificationPort, AuthSessionPort
- Dependances a remplacer: Retrofit/OkHttp calls directs par clients abstraction partages
- Conservables: regles metier et validations
- Reecriture Kotlin: orchestrations metier + result types scelles + coroutines
2. Jira clients (JiraClient/JiraReader)
- Extraire: DTO, routes, mapping reponse/erreur, transition/status rules
- Supprimer: BuildConfig direct, Thread manuel, classes Android utilitaires
- Interfaces: JiraSoftwareApi, JiraAssetsApi, RetryPolicy, AuthHeaderProvider
- Remplacer: callbacks par suspend functions
3. IA (GeminiGatewayClient/TicketMatcher/parsers)
- Extraire: prompt builders, parseurs JSON, regles de validation de sortie
- Supprimer: fallback camera/bitmap Android du coeur metier
- Interfaces: VisionAnalyzerPort, LlmAssignmentPort
4. Modeles Room
- Extraire: data classes Kotlin de domaine sans annotations Room
- Supprimer: annotations Room des modeles partages
- Interfaces: repositories CRUD et query abstraites

## Ordre de migration recommande
Phase 0: Preparation
- Fichiers: docs + pipeline CI Android
- Objectif: verrouiller baseline Android (build/tests)
- Risque: drift de comportement
- Tests: assembleDebug + testDebugUnitTest + connected tests critiques
- Critere validation: baseline verte
Phase 1: Modeles/domain
- Fichiers: model/* (domain mirror)
- Objectif: modeles Kotlin purs dans shared/domain
- Risque: divergence mappings
- Tests: mapping tests Android <-> domain
- Go/no-go: parite des serialisations et calculs
Phase 2: Validation/business logic
- Fichiers: MainController, TicketMatcher, parsers
- Objectif: extraire regles vers use-cases Kotlin
- Risque: regressions flux stock entry/exit
- Tests: unit tests de use-cases + scenarii QA existants
- Go/no-go: sorties identiques Android
Phase 3: Networking/API
- Fichiers: JiraClient, JiraReader, ApiService, AIService, GeminiGatewayClient
- Objectif: contrats reseau partages (Ktor)
- Risque: erreurs auth/timeouts
- Tests: integration mocks + replay de cas erreurs
- Go/no-go: taux succes identique baseline
Phase 4: Repositories
- Fichiers: DAO wrappers, controller orchestration
- Objectif: repositories abstraits dans shared
- Risque: mismatch transactions
- Tests: contract tests repository
- Go/no-go: invariants metier conserves
Phase 5: Offline/sync
- Fichiers: AppDatabase, DAOs, workers/sync policies
- Objectif: policy retry/conflit partagee + impl stockage plateforme
- Risque: doublons/perte operations pending
- Tests: idempotence + interruption reseau
- Go/no-go: zero regression sur replay offline
Phase 6: Authentification
- Fichiers: Auth0Manager, AuthBearerInterceptor, SessionManager
- Objectif: interfaces auth communes OIDC+PKCE
- Risque: sessions invalides/refresh token
- Tests: login/logout/refresh + flow vista-okta
- Go/no-go: securite et comportement inchanges
Phase 7: Stock Entry
- Fichiers: ScanAssetActivity, ReceivePackageActivity, PackageLabelActivity
- Objectif: brancher use-cases shared cote Android
- Risque: UX/hardware regressions
- Tests: E2E scan + reconciliation PO
- Go/no-go: scenario complet vert
Phase 8: Stock Exit
- Fichiers: ScanOutActivity, TicketListActivity, TicketMatcher integration
- Objectif: brancher orchestration shared sortie
- Risque: calcul disponibilite, Jira transition
- Tests: E2E sortie, idempotence, retry Jira
- Go/no-go: pas de duplication et parite fonctionnelle
Phase 9: iOS UI
- Fichiers: iosApp nouveaux ecrans
- Objectif: ecrans iOS consommant shared
- Risque: ecart UX/fonctionnel
- Tests: parity scenarios vs Android reference
- Go/no-go: use-cases critiques iOS valides
Phase 10: Camera/barcode/OCR iOS
- Objectif: implementation native iOS des ports vision
- Risque: precision extraction
- Tests: dataset compare Android/iOS
- Go/no-go: precision minimale definie
Phase 11: Notifications
- Objectif: parity des alertes Android/iOS
- Risque: livraison push/background
- Tests: push fonctionnel et fallback webhooks
- Go/no-go: SLA notifications respecte
Phase 12: Tests et stabilisation
- Objectif: hardening final multiplateforme
- Tests: unit, integration, contract, E2E, offline, security
- Go/no-go: Android inchange + iOS parity acceptable

## Recommandation finale
1. Partager en priorite: modeles domaine Kotlin, use-cases, regles de validation, contrats API/repository/sync.
2. Laisser Android-only: UI activities/adapters/xml, CameraX, Firebase service, notifications natives.
3. Developper iOS-specifique: UI, camera/vision, notifications, secure storage, lifecycle background.
4. Refactorer avant partage: MainController, Jira clients, IA gateway/matcher, modeles Room.
5. Priorite absolue: Android reste reference fonctionnelle a chaque phase.

