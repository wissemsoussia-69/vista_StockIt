package com.example.stockit.shared.config

/**
 * StockIT — Configuration runtime (équivalent multiplateforme de BuildConfig).
 *
 * Les valeurs par défaut sont injectées au build depuis l'environnement ou
 * `.env.sh` (cf. shared/build.gradle.kts → GeneratedConfig). Elles restent
 * modifiables à l'exécution (ex. iOS : surcharge depuis Info.plist / écran debug).
 */
object StockItConfig {
    var cimpressGatewayKey: String = GeneratedConfig.CIMPRESS_GATEWAY_KEY
    var gatewayUrl: String = GeneratedConfig.GATEWAY_URL.ifBlank { "https://gateway.ai.cimpress.io/v1" }
    var cimpressVisionModel: String =
        GeneratedConfig.CIMPRESS_VISION_MODEL.ifBlank { "@Anthropic/eu.anthropic.claude-opus-4-8" }

    /** Option B : Claude direct (api.anthropic.com), utilisé si Cimpress n'est pas configuré. */
    var claudeApiKey: String = GeneratedConfig.CLAUDE_API_KEY
    var claudeModel: String = GeneratedConfig.CLAUDE_MODEL.ifBlank { "claude-3-5-sonnet-latest" }

    var portkeyApiKey: String = GeneratedConfig.PORTKEY_API_KEY
    var geminiApiKey: String = GeneratedConfig.GEMINI_API_KEY

    var slackWebhookUrl: String = GeneratedConfig.SLACK_WEBHOOK_URL
    var slackBotToken: String = GeneratedConfig.SLACK_BOT_TOKEN
    var slackChannelId: String = GeneratedConfig.SLACK_CHANNEL_ID.ifBlank { "#etx-alerts" }

    /** Relais Slack n8n (POST /webhook/stockit/slack) — prioritaire sur le webhook et le bot. */
    var stockitSlackWebhookUrl: String = GeneratedConfig.STOCKIT_SLACK_WEBHOOK_URL

    var jiraUserEmail: String = GeneratedConfig.JIRA_USER_EMAIL
    var jiraApiToken: String = GeneratedConfig.JIRA_API_TOKEN
    var jiraBaseUrl: String = GeneratedConfig.JIRA_BASE_URL
    var jiraProjectKey: String = GeneratedConfig.JIRA_PROJECT_KEY
    var jiraIssueType: String = GeneratedConfig.JIRA_ISSUE_TYPE
    var jiraCostCenter: String = GeneratedConfig.JIRA_COST_CENTER
    var jiraComponent: String = GeneratedConfig.JIRA_COMPONENT

    /** Jira Assets (CMDB) : schéma « Stock IT Testing » (SIT) et types d'objets. */
    var jiraAssetsSchemaId: String = GeneratedConfig.JIRA_ASSETS_SCHEMA_ID
    var jiraAssetsTypePeripherals: String = GeneratedConfig.JIRA_ASSETS_TYPE_PERIPHERALS
    var jiraAssetsTypeComputers: String = GeneratedConfig.JIRA_ASSETS_TYPE_COMPUTERS
    var jiraAssetsTypeMonitors: String = GeneratedConfig.JIRA_ASSETS_TYPE_MONITORS
    /** Valeur « Geo Location » des objets créés (alignée sur les objets existants). */
    var jiraAssetsLocation: String = GeneratedConfig.JIRA_ASSETS_LOCATION.ifBlank { "Tunis" }

    var huggingFaceToken: String = GeneratedConfig.HUGGING_FACE_TOKEN
    var sendgridApiKey: String = GeneratedConfig.SENDGRID_API_KEY

    var stockitWebhookUrl: String = GeneratedConfig.STOCKIT_WEBHOOK_URL
    var stockitWebhookSecret: String = GeneratedConfig.STOCKIT_WEBHOOK_SECRET

    var auth0Audience: String = GeneratedConfig.AUTH0_AUDIENCE

    /** Webhook n8n des mouvements de stock (POST /webhook/stockit/event). */
    var stockitEventWebhookUrl: String = GeneratedConfig.STOCKIT_EVENT_WEBHOOK_URL

    /** Webhook n8n du statut des actifs (POST /webhook/stockit/asset-status). */
    var stockitAssetStatusWebhookUrl: String = GeneratedConfig.STOCKIT_ASSET_STATUS_WEBHOOK_URL

    /** Rôle attribué à un employé Okta sans groupe StockIT reconnu (USER = lecture seule). */
    var ssoDefaultRole: String = GeneratedConfig.STOCKIT_SSO_DEFAULT_ROLE.ifBlank { "USER" }

    /**
     * Backend réclamations / alertes (Retrofit `ApiService` côté Android).
     * Android émulateur : http://10.0.2.2/api/ — iOS simulateur : http://localhost/api/
     */
    var apiBaseUrl: String = GeneratedConfig.STOCKIT_API_BASE_URL

    /**
     * Mode test : aucune action externe réelle (pas de ticket Jira créé, pas de message Slack,
     * pas d'e-mail ni de webhook n8n). Les lectures (tickets Jira, Claude) restent actives.
     * Activable dans Paramètres de l'app.
     */
    var testMode: Boolean = false

    /**
     * Langue de l'interface ("fr" ou "en"), choisie dans Paramètres.
     * Utilisée pour les textes affichés et la langue de réponse de Claude.
     * Les messages destinés à l'équipe (Slack, e-mails n8n, tickets Jira, journal d'audit) restent en français.
     */
    var language: String = "fr"
    val isEnglish: Boolean get() = language == "en"

    /** Destinataire par défaut des e-mails n8n (identique à l'app Android). */
    var defaultRecipientEmail: String = GeneratedConfig.STOCKIT_DEFAULT_EMAIL

    // Auth0 / Okta (lus depuis .env.sh ; jamais en dur dans le code publié)
    val auth0Domain: String get() = GeneratedConfig.AUTH0_DOMAIN
    val auth0ClientId: String get() = GeneratedConfig.AUTH0_CLIENT_ID
    val auth0Connection: String get() = GeneratedConfig.AUTH0_CONNECTION
    val auth0CallbackUrl: String get() = GeneratedConfig.AUTH0_CALLBACK_URL
    val auth0LogoutUrl: String get() = GeneratedConfig.AUTH0_LOGOUT_URL

    /** Applique une surcharge clé/valeur (noms identiques aux variables d'environnement). */
    fun applyOverride(key: String, value: String) {
        if (value.isBlank()) return
        when (key) {
            "CIMPRESS_GATEWAY_KEY" -> cimpressGatewayKey = value
            "GATEWAY_URL" -> gatewayUrl = value
            "CIMPRESS_VISION_MODEL" -> cimpressVisionModel = value
            "PORTKEY_API_KEY" -> portkeyApiKey = value
            "GEMINI_API_KEY" -> geminiApiKey = value
            "SLACK_WEBHOOK_URL" -> slackWebhookUrl = value
            "SLACK_BOT_TOKEN" -> slackBotToken = value
            "JIRA_USER_EMAIL" -> jiraUserEmail = value
            "JIRA_API_TOKEN" -> jiraApiToken = value
            "JIRA_BASE_URL" -> jiraBaseUrl = value
            "JIRA_PROJECT_KEY" -> jiraProjectKey = value
            "JIRA_ISSUE_TYPE" -> jiraIssueType = value
            "JIRA_COST_CENTER" -> jiraCostCenter = value
            "JIRA_COMPONENT" -> jiraComponent = value
            "HUGGING_FACE_TOKEN" -> huggingFaceToken = value
            "SENDGRID_API_KEY" -> sendgridApiKey = value
            "STOCKIT_WEBHOOK_URL" -> stockitWebhookUrl = value
            "STOCKIT_WEBHOOK_SECRET" -> stockitWebhookSecret = value
            "AUTH0_AUDIENCE" -> auth0Audience = value
            "STOCKIT_API_BASE_URL" -> apiBaseUrl = value
            "STOCKIT_EVENT_WEBHOOK_URL" -> stockitEventWebhookUrl = value
            "STOCKIT_SLACK_WEBHOOK_URL" -> stockitSlackWebhookUrl = value
            "STOCKIT_ASSET_STATUS_WEBHOOK_URL" -> stockitAssetStatusWebhookUrl = value
            "CLAUDE_API_KEY" -> claudeApiKey = value
            "CLAUDE_MODEL" -> claudeModel = value
            "STOCKIT_SSO_DEFAULT_ROLE" -> ssoDefaultRole = value
            "SLACK_CHANNEL_ID" -> slackChannelId = value
            "JIRA_ASSETS_SCHEMA_ID" -> jiraAssetsSchemaId = value
            "JIRA_ASSETS_TYPE_PERIPHERALS" -> jiraAssetsTypePeripherals = value
            "JIRA_ASSETS_TYPE_COMPUTERS" -> jiraAssetsTypeComputers = value
            "JIRA_ASSETS_TYPE_MONITORS" -> jiraAssetsTypeMonitors = value
            "JIRA_ASSETS_LOCATION" -> jiraAssetsLocation = value
        }
    }

    val isCimpressConfigured: Boolean get() = cimpressGatewayKey.isNotBlank() && gatewayUrl.isNotBlank()
    val isClaudeConfigured: Boolean get() = claudeApiKey.isNotBlank()
    /** Au moins un fournisseur IA texte disponible (Cimpress ou Claude direct). */
    val isAIConfigured: Boolean get() = isCimpressConfigured || isClaudeConfigured
    val isJiraConfigured: Boolean
        get() = jiraBaseUrl.isNotBlank() && jiraUserEmail.isNotBlank() && jiraApiToken.isNotBlank()
    val isJiraAssetsConfigured: Boolean
        get() = isJiraConfigured && jiraAssetsSchemaId.isNotBlank() && jiraAssetsTypePeripherals.isNotBlank()
}
