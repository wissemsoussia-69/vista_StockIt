# StockIT — Checklist finalisation SSO Vista (Okta/Auth0)

Ce document liste les actions **côté tenant IAM** (non-code) à réaliser pour
stabiliser le SSO Vista en production. Le code Android est prêt et
lit tout depuis les variables d'environnement / `strings.xml` — aucun autre
changement de code n'est requis pour ces étapes.

## 1 · Tenant réel Vista

Actuellement :
```xml
<!-- app/src/main/res/values/strings.xml -->
<string name="com_auth0_domain">cimpress.auth0.com</string>
<string name="com_auth0_client_id">46HIhCVTaDjXJzQI66jslidCCasu7pgc</string>
<string name="com_auth0_scheme">com.example.stockit</string>
```

Note d'incident (résolu) : le client `82d4a7571ba3ca5c525cf0dcdc956cfb` a été
rejeté par le tenant Cimpress avec `invalid_request: Unknown client`.

À demander à l'équipe IAM Vista :

- `com_auth0_domain` → domaine actif validé : `cimpress.auth0.com`.
- `com_auth0_client_id` → client ID actif validé :
  `46HIhCVTaDjXJzQI66jslidCCasu7pgc`.
- Type de client : **Public** (natif). Pas de secret côté APK.

## 2 · Callback + Logout URLs à enregistrer dans le tenant

Le SDK Auth0/Okta construit l'URL de retour à partir du `com_auth0_scheme` +
du domaine. Deux URLs doivent être whitelistées dans les paramètres de
l'application :

```
com.example.stockit://<DOMAINE>/android/com.example.stockit/callback
com.example.stockit://<DOMAINE>/android/com.example.stockit/logout
```

Remplacer `com.example.stockit` par le vrai package quand l'étape 5 sera faite,
et `<DOMAINE>` par le domaine réel du tenant.

Champs à remplir dans le dashboard :

- **Allowed Callback URLs** → URL callback ci-dessus.
- **Allowed Logout URLs** → URL logout ci-dessus.
- **Allowed Origins (CORS)** → non nécessaire pour une app mobile.

## 3 · Grant type Refresh Token

Le code demande le scope `offline_access`
(`app/src/main/java/com/example/stockit/util/Auth0Manager.java`), ce qui suppose
que le grant type **Refresh Token** soit activé dans le tenant :

- Application → **Advanced Settings** → **Grant Types** → cocher
  `Refresh Token` (en plus de `Authorization Code`).
- **Refresh Token Rotation** : activer (recommandé — chaque refresh remplace
  le précédent).
- **Refresh Token Expiration** :
  - Idle : 15 jours (par défaut Auth0).
  - Absolute : selon politique Vista (ex. 30 jours).

Sans ça, `Credentials.refreshToken` sera `null` → la session expire au bout
d'une heure et l'utilisateur est renvoyé au login.

## 4 · Post-Login Action pour les rôles

Le code (`Auth0Manager.extractRole`) attend un claim custom parmi :

```
https://vista.com/roles
https://vista.com/groups
roles
groups
```

Sans cette Action, `extractRole` retombe systématiquement sur `USER` et
toutes les fonctions admin/manager sont désactivées.

À déployer dans le tenant :

1. **Actions** → **Library** → **Build Custom** → « Add Roles Claim ».
2. Trigger : **Post Login**.
3. Code (JavaScript) :
   ```javascript
   exports.onExecutePostLogin = async (event, api) => {
     const NS = "https://vista.com/";
     if (event.authorization) {
       api.idToken.setCustomClaim(NS + "roles",  event.authorization.roles);
       api.idToken.setCustomClaim(NS + "groups", event.authorization.groups);
       api.accessToken.setCustomClaim(NS + "roles",  event.authorization.roles);
       api.accessToken.setCustomClaim(NS + "groups", event.authorization.groups);
     }
   };
   ```
4. **Deploy** puis glisser l'Action dans **Actions → Flows → Login**.

Rôles attendus (matching côté client) : `ADMIN`, `MANAGER`, `TECHNICIEN`.
Tout autre libellé → `USER`.

## 5 · Renommer l'`applicationId`

`app/build.gradle.kts`
```kts
applicationId = "com.example.stockit"   // ← à changer
```

Impact :
- Le `com_auth0_scheme` doit rester égal à l'`applicationId` (convention
  Auth0). Mettre à jour `strings.xml` en même temps.
- Les URLs de callback/logout enregistrées dans le tenant (étape 2) doivent
  être régénérées avec le nouveau nom.
- L'app deviendra une nouvelle installation sur les téléphones (data isolée).

Proposition : `com.vista.stockit` ou `com.vistaprint.stockit`.

## 6 · Variables d'environnement à définir (côté build)

Une fois les étapes 1–5 faites, mettre à jour `set_env.ps1` / `.env.sh` :

```powershell
# Audience de l'API backend Vista (émis par Auth0 dans l'access_token)
$env:AUTH0_AUDIENCE = "https://api.vista.com/stockit"

# Opt-in biométrie (Face/Empreinte à chaque refresh de token)
$env:AUTH0_REQUIRE_BIOMETRIC = "1"
```

Ces deux variables sont câblées dans `app/build.gradle.kts` via
`buildConfigField(...)` et lues par `Auth0Manager` sans autre modification.

## 7 · Validation

Après avoir tout appliqué :

1. `./gradlew installDebug`
2. Lancer l'app → **Login SSO** → doit passer sans profile picker (release)
   ou avec profile picker (debug).
3. Après login, `adb shell pm dump com.example.stockit | grep BuildConfig`
   ou inspecter `logcat` — `Authorization: Bearer eyJ…` doit apparaître dans
   les requêtes vers le backend Vista.
4. Modifier le rôle de l'utilisateur dans le tenant (Users → Roles), se
   reconnecter → l'UI admin/manager doit apparaître/disparaître en
   conséquence.

## Résumé — état actuel du code

| Étape                           | Code prêt | Config tenant requise |
|---------------------------------|:---------:|:---------------------:|
| 1 · Tenant réel Vista           | ✅        | ❌ (à faire)          |
| 2 · Callback / Logout URLs      | ✅        | ❌ (à faire)          |
| 3 · Grant Refresh Token         | ✅        | ❌ (à faire)          |
| 4 · Claim roles (Action)        | ✅        | ❌ (à faire)          |
| 5 · Rename applicationId        | à faire   | dépend de #2          |
| 6 · Purge credentials pourris   | ✅        | —                     |
| 7 · Interceptor Bearer          | ✅        | —                     |
| 8 · Audience API                | ✅        | dépend de #1          |
| 9 · Biométrie (opt-in)          | ✅        | —                     |
| 10 · Bypass local retiré (rel.) | ✅        | —                     |
