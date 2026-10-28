plugins {
    alias(libs.plugins.android.application)
    // alias(libs.plugins.google.services) // Nécessite google-services.json
}

// StockIT PFE — Lit une variable d'environnement, sinon `.env.sh` à la racine (comme le module
// shared / iOS), sinon retourne fallback. Évite un APK sans clés (SSO « non configuré ») quand
// le build est lancé depuis Android Studio ou un terminal où `.env.sh` n'a pas été sourcé.
val envFileValues: Map<String, String> = run {
    val f = rootProject.file(".env.sh")
    if (!f.exists()) return@run emptyMap()
    val rx = Regex("""^\s*(?:export\s+)?([A-Z0-9_]+)\s*=\s*(.*)\s*$""")
    f.readLines().mapNotNull { line ->
        if (line.trimStart().startsWith("#")) return@mapNotNull null
        val m = rx.find(line) ?: return@mapNotNull null
        var v = m.groupValues[2].trim()
        val quoted = Regex("""^(["'])(.*?)\1""").find(v)
        v = if (quoted != null) quoted.groupValues[2] else v.substringBefore(" #").trim()
        m.groupValues[1] to v
    }.toMap()
}

fun env(name: String, fallback: String): String =
    (System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: envFileValues[name]?.takeIf { it.isNotBlank() }
        ?: fallback).replace("\"", "\\\"")

android {
    namespace = "com.example.stockit"
    compileSdk = 37

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    defaultConfig {
        applicationId = "com.example.stockit"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // --- Clés lues UNIQUEMENT depuis l'environnement (.env.sh / set_env.ps1, non commités).
        //     Les fallbacks sont volontairement vides / placeholders : AUCUN vrai secret ici. ---
        buildConfigField("String", "STOCKIT_DEFAULT_EMAIL", "\"${env("STOCKIT_DEFAULT_EMAIL", "")}\"")
        // Auth0 (Vista SSO) — valeurs lues dans .env.sh, injectées en ressources (aucune valeur dans le code publié)
        resValue("string", "com_auth0_domain", env("AUTH0_DOMAIN", ""))
        resValue("string", "com_auth0_client_id", env("AUTH0_CLIENT_ID", ""))
        resValue("string", "com_auth0_connection", env("AUTH0_CONNECTION", ""))
        buildConfigField("String", "SLACK_BOT_TOKEN",   "\"${env("SLACK_BOT_TOKEN",   "")}\"")
        buildConfigField("String", "JIRA_API_TOKEN",    "\"${env("JIRA_API_TOKEN",    "")}\"")
        buildConfigField("String", "JIRA_USER_EMAIL",   "\"${env("JIRA_USER_EMAIL",   "")}\"")
        buildConfigField("String", "JIRA_BASE_URL",     "\"${env("JIRA_BASE_URL",     "")}\"")
        buildConfigField("String", "JIRA_PROJECT_KEY",  "\"${env("JIRA_PROJECT_KEY",  "")}\"")
        buildConfigField("String", "JIRA_ISSUE_TYPE",   "\"${env("JIRA_ISSUE_TYPE",   "")}\"")
        buildConfigField("String", "JIRA_COST_CENTER",  "\"${env("JIRA_COST_CENTER",  "")}\"")
        buildConfigField("String", "JIRA_COMPONENT",    "\"${env("JIRA_COMPONENT",    "")}\"")

        // --- Passerelle IA principale : Cimpress Gateway (Vistaprint) ---
        buildConfigField("String", "GATEWAY_URL",             "\"${env("GATEWAY_URL",             "")}\"")
        buildConfigField("String", "CIMPRESS_GATEWAY_KEY",    "\"${env("CIMPRESS_GATEWAY_KEY",    "")}\"")
        buildConfigField("String", "CIMPRESS_VISION_MODEL",   "\"${env("CIMPRESS_VISION_MODEL",   "")}\"")
        buildConfigField("String", "SLACK_WEBHOOK_URL",       "\"${env("SLACK_WEBHOOK_URL",       "")}\"")

        // --- Webhook interne StockIT (n8n) : envoi de
        // rapports, notifications e-mail et alertes vers le workflow central. ---
        buildConfigField("String", "STOCKIT_WEBHOOK_URL",     "\"${env("STOCKIT_WEBHOOK_URL",     "")}\"")
        buildConfigField("String", "STOCKIT_WEBHOOK_SECRET",  "\"${env("STOCKIT_WEBHOOK_SECRET",  "")}\"")

        // --- Auth0 (Vista SSO) ---
        // Le SDK Auth0 lit ces placeholders dans le manifeste pour enregistrer
        // automatiquement sa RedirectActivity (aucune activité à déclarer
        // manuellement dans AndroidManifest.xml).
        manifestPlaceholders["auth0Domain"] = "@string/com_auth0_domain"
        manifestPlaceholders["auth0Scheme"] = "@string/com_auth0_scheme"

        // Opt-in : exiger biométrie (Face/Empreinte) pour déchiffrer les tokens
        // Auth0 stockés par SecureCredentialsManager. Off par défaut pour ne
        // pas casser le flow de dev ; activer avec AUTH0_REQUIRE_BIOMETRIC=1.
        buildConfigField(
            "boolean",
            "AUTH0_REQUIRE_BIOMETRIC",
            (env("AUTH0_REQUIRE_BIOMETRIC", "0") == "1").toString()
        )

        // Audience de l'API backend Vista. Quand non vide, Auth0 émet un
        // access_token JWT signé destiné à cette audience ; à injecter en
        // Authorization: Bearer <token> côté OkHttp. Vide en dev = ID token
        // seul (aucune API back protégée par Okta).
        buildConfigField(
            "String",
            "AUTH0_AUDIENCE",
            "\"${env("AUTH0_AUDIENCE", "")}\""
        )
    }

    buildTypes {
        release {
            // TM-16 : minification + shrink activés (les secrets compilés restent
            // extraisibles, mais nettement moins lisibles — la vraie parade est un BFF).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    // Traductions partagées avec l'app iOS (clé = texte français) : en.lproj/Localizable.strings.
    sourceSets {
        getByName("main") {
            assets.srcDir("../iosApp/StockIT/Resources")
        }
    }

    lint {
        disable += listOf(
            "DuplicatePlatformClasses",
            "MissingTranslation"
        )
    }
}

dependencies {
    // Logique métier partagée avec iOS (KMP) : matcher produits, vision IA,
    // prévisions, brouillons de commande, Jira Assets, qualité IA.
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.camera.core)
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:image-labeling:17.0.7")
    implementation(libs.google.mlkit.text)
    implementation(libs.google.mlkit.barcode)
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.mlkit:object-detection-custom:17.0.2")
    implementation("com.itextpdf:itext7-core:9.0.0")
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.work.runtime)
    implementation(libs.google.material)
    implementation("com.itextpdf:itext7-core:9.0.0")
    implementation(libs.androidx.recyclerview)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation(libs.androidx.cardview)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.androidx.room.compiler)
    // Auth0 Android SDK — Vista SSO (Universal Login via Chrome Custom Tabs).
    // TM-31 : version épinglée (pas de 3.+ flottant).
    implementation("com.auth0.android:auth0:3.21.3")
    testImplementation(libs.junit)
    // org.json réel pour les tests unitaires JVM (le stub android.jar lève « not mocked »).
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
