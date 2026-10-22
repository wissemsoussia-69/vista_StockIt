// =============================================================================
//  StockIT — module Kotlin Multiplatform partagé (Android + iOS).
//
//  Contient toute la logique métier indépendante de l'UI :
//    - modèles + base locale (SQLDelight, équivalent du Room Android)
//    - StockItRepository (équivalent de MainController)
//    - clients réseau Ktor : Jira, Slack, webhook n8n, Cimpress/Portkey/Gemini/HF,
//      API réclamations
//    - TicketMatcher, DeliveryNoteParser, PackageLabelParser (partie LLM)
//
//  iOS : produit le framework `Shared.framework` consommé par iosApp (SwiftUI).
//        Xcode l'appelle via `./gradlew :shared:embedAndSignAppleFrameworkForXcode`.
// =============================================================================
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.sqldelight)
}

// -----------------------------------------------------------------------------
//  Configuration (clés API) : même principe que BuildConfig côté Android.
//  Valeur lue dans l'environnement, sinon dans `.env.sh` à la racine (utile pour
//  les builds lancés depuis Xcode, qui n'héritent pas des variables du shell).
//  AUCUN secret n'est commité : le fichier généré vit dans build/.
// -----------------------------------------------------------------------------
val configKeys = listOf(
    "CIMPRESS_GATEWAY_KEY", "GATEWAY_URL", "CIMPRESS_VISION_MODEL",
    "PORTKEY_API_KEY", "GEMINI_API_KEY",
    "SLACK_WEBHOOK_URL", "SLACK_BOT_TOKEN",
    "JIRA_USER_EMAIL", "JIRA_API_TOKEN", "JIRA_BASE_URL", "JIRA_PROJECT_KEY",
    "JIRA_ISSUE_TYPE", "JIRA_COST_CENTER", "JIRA_COMPONENT",
    "HUGGING_FACE_TOKEN", "SENDGRID_API_KEY",
    "STOCKIT_WEBHOOK_URL", "STOCKIT_WEBHOOK_SECRET",
    "AUTH0_AUDIENCE", "STOCKIT_API_BASE_URL",
    "STOCKIT_EVENT_WEBHOOK_URL", "STOCKIT_SSO_DEFAULT_ROLE",
    "CLAUDE_API_KEY", "CLAUDE_MODEL", "STOCKIT_ASSET_STATUS_WEBHOOK_URL", "SLACK_CHANNEL_ID",
    "STOCKIT_SLACK_WEBHOOK_URL", "STOCKIT_DEFAULT_EMAIL", "AUTH0_DOMAIN", "AUTH0_CLIENT_ID", "AUTH0_CONNECTION",
    "AUTH0_CALLBACK_URL", "AUTH0_LOGOUT_URL",
    "JIRA_ASSETS_SCHEMA_ID", "JIRA_ASSETS_TYPE_PERIPHERALS", "JIRA_ASSETS_TYPE_COMPUTERS",
    "JIRA_ASSETS_TYPE_MONITORS", "JIRA_ASSETS_LOCATION",
)

fun readEnvFile(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    val rx = Regex("""^\s*(?:export\s+)?([A-Z0-9_]+)\s*=\s*(.*)\s*$""")
    return file.readLines().mapNotNull { line ->
        if (line.trimStart().startsWith("#")) return@mapNotNull null
        val m = rx.find(line) ?: return@mapNotNull null
        var v = m.groupValues[2].trim()
        // Valeur entre guillemets : on garde le contenu, on ignore un éventuel commentaire après.
        val quoted = Regex("""^(["'])(.*?)\1""").find(v)
        v = if (quoted != null) quoted.groupValues[2] else v.substringBefore(" #").trim()
        m.groupValues[1] to v
    }.toMap()
}

val generatedConfigDir = layout.buildDirectory.dir("generated/stockitConfig/commonMain/kotlin")
val envFile = rootProject.file(".env.sh")

val generateStockItConfig by tasks.registering {
    description = "Génère StockItConfig.kt à partir de l'environnement / .env.sh"
    val outDir = generatedConfigDir
    val values: Map<String, String> = run {
        val fromFile = readEnvFile(envFile)
        configKeys.associateWith { k ->
            System.getenv(k)?.takeIf { it.isNotBlank() } ?: fromFile[k].orEmpty()
        }
    }
    inputs.property("values", values.toSortedMap().toString())
    outputs.dir(outDir)
    doLast {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        val body = buildString {
            appendLine("// FICHIER GÉNÉRÉ — ne pas modifier, ne pas committer (voir shared/build.gradle.kts).")
            appendLine("package com.example.stockit.shared.config")
            appendLine()
            appendLine("internal object GeneratedConfig {")
            values.forEach { (k, v) -> appendLine("    const val $k: String = \"${esc(v)}\"") }
            appendLine("}")
        }
        val f = outDir.get().file("com/example/stockit/shared/config/GeneratedConfig.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(body)
    }
}

kotlin {
    android {
        namespace = "com.example.stockit.shared"
        compileSdk = 35
        minSdk = 26
        withHostTestBuilder {}
    }

    // iPhone (arm64) + simulateur Apple Silicon. Ajouter iosX64() pour un Mac Intel.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            // Autorise l'appel des fonctions suspend (async Swift) hors du main thread.
            binaryOption("objcExportSuspendFunctionLaunchThreadRestriction", "none")
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateStockItConfig)
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.ktor.client.okhttp)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}

sqldelight {
    databases {
        create("StockItDatabase") {
            packageName.set("com.example.stockit.shared.db")
        }
    }
}
