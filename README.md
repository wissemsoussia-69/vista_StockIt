# StockIT

Application de gestion du stock informatique (scan d'équipements par IA, réception de colis,
sorties sur tickets Jira, synchronisation avec Jira Assets), disponible sur **iOS** et **Android**.

- **iOS** : SwiftUI — dossier [`iosApp/`](iosApp)
- **Android** : Java — dossier [`app/`](app)
- **Logique partagée** : Kotlin Multiplatform — dossier [`shared/`](shared)
  (base locale SQLDelight, Jira / Jira Assets, Slack, webhooks n8n, IA Claude, parsers facture & étiquette)

## Fonctionnalités

- Connexion SSO (Auth0 / Okta) ; session fermée à chaque sortie de l'app
- Scan d'équipement par Claude (marque, modèle, n° de série) avec confirmation
- Réception de colis : lecture de facture (PO) et d'étiquette carton
- Sortie d'équipement répartie sur les tickets Jira (proposition de Claude, liste, ID manuel)
- Inventaire synchronisé avec **Jira Assets** (dans les deux sens)
- Prévision des besoins, brouillon de commande, qualité de l'IA, rapport PDF
- Alertes Slack et e-mails via n8n, avec file d'attente hors ligne
- Interface en **français / anglais**, mode sombre, mode test

## Démarrage

```bash
cp .env.sh.example .env.sh     # puis renseigner vos propres valeurs (jamais publiées)
```

**iOS** (Xcode 15+, JDK 17+, XcodeGen) :

```bash
brew install openjdk@21 xcodegen
cd iosApp && xcodegen generate && open StockIT.xcodeproj
```

Choisissez votre équipe de signature, puis ⌘R. Le détail est dans [README_IOS.md](README_IOS.md).

**Android** :

```bash
source .env.sh && ./gradlew :app:installDebug
```

## Tests

```bash
./gradlew :shared:testAndroidHostTest                 # logique partagée
cd iosApp && xcodebuild test -scheme StockIT \
  -destination 'platform=iOS Simulator,name=iPhone 17' # parcours UI iOS
```

## Configuration et sécurité

Toutes les clés et adresses (IA, Jira, Slack, n8n, Auth0) sont lues depuis `.env.sh`, qui est ignoré par git.
Aucun secret n'est présent dans ce dépôt. Attention : une valeur intégrée au build d'une application mobile
peut être extraite de l'application installée. Pour une mise en production, placez les appels sensibles
derrière un serveur.
