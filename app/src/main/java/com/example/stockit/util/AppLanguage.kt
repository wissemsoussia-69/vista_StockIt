package com.example.stockit.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.stockit.shared.config.StockItConfig
import java.util.Locale

/**
 * Langue de l'application (choisie dans Paramètres), indépendante de la langue du téléphone — identique iOS.
 *
 *  - Les layouts XML utilisent les ressources values/ (fr) et values-en/ (en).
 *  - Les textes construits en code utilisent [L] : clé = texte français, traduction lue dans le même
 *    fichier que l'app iOS (iosApp/StockIT/Resources/en.lproj/Localizable.strings, embarqué en asset).
 *  - Le module partagé (textes Kotlin + langue de réponse de Claude) suit la même langue.
 */
object AppLanguage {

    const val FR = "fr"
    const val EN = "en"
    private const val PREFS = "stockit_settings"
    private const val KEY = "app_language"

    @Volatile private var appContext: Context? = null
    @Volatile private var table: Map<String, String> = emptyMap()
    @Volatile private var loadedFor: String? = null

    /** Noms affichés dans le sélecteur (toujours dans leur propre langue). */
    @JvmStatic fun codes(): Array<String> = arrayOf(FR, EN)
    @JvmStatic fun displayName(code: String): String = if (code == EN) "English" else "Français"

    /** Langue courante (préférence ; par défaut : anglais si le téléphone est en anglais, sinon français). */
    @JvmStatic
    fun current(context: Context): String {
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (saved == FR || saved == EN) return saved
        return if (Locale.getDefault().language == EN) EN else FR
    }

    @JvmStatic fun isEnglish(): Boolean = StockItConfig.language == EN

    /** À appeler au démarrage (Application.onCreate) : applique la langue à toute l'app. */
    @JvmStatic
    fun apply(context: Context) {
        appContext = context.applicationContext
        val code = current(context)
        StockItConfig.language = code
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
    }

    /** Change la langue : les écrans ouverts sont recréés immédiatement dans la nouvelle langue. */
    @JvmStatic
    fun set(context: Context, code: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
        apply(context)
    }

    @JvmStatic fun locale(): Locale = if (isEnglish()) Locale.ENGLISH else Locale.FRANCE

    /**
     * Texte traduit dans la langue de l'app (même clés que L(…) côté iOS).
     * `L("Capture échouée")` ; avec arguments : `L("%@ ajouté au stock", name)`.
     */
    @JvmStatic
    fun L(key: String, vararg args: Any?): String {
        val format = if (isEnglish()) (translations()[key] ?: key) else key
        val f = format.replace("%@", "%s")
        return if (args.isEmpty()) f.replace("%%", "%") else runCatching { String.format(locale(), f, *args) }.getOrDefault(f)
    }

    /** Traduit une valeur stockée en base (statut, motif…) pour l'affichage uniquement (iOS LV). */
    @JvmStatic
    fun LV(stored: String?): String {
        if (stored.isNullOrEmpty() || !isEnglish()) return stored ?: ""
        translations()[stored]?.let { return it }
        var out: String = stored
        for ((fr, en) in STORED_PHRASES) out = out.replace(fr, en)
        return out
    }

    private val STORED_PHRASES = listOf(
        "Sortie sur ticket" to "Check-out on ticket", "Sortie libre" to "Free check-out", "Choix manuel" to "Manual choice",
        "Nouvel article ajouté" to "New item added", "Ajustement manuel" to "Manual adjustment", "Article supprimé" to "Item deleted",
        "Kit complet (Alim + HDMI)" to "Complete kit (Power + HDMI)", "Scan IA StockIT PFE" to "StockIT AI scan",
        "Saisie manuelle" to "Manual entry", "Ajout manuel" to "Manual entry", "Réception" to "Receiving", "Prêt" to "Loan",
        "Arrivée stock" to "Stock arrival", "Sortie pour bureau" to "Out to office", "Système" to "System", "Scan IA" to "AI scan",
    )

    private fun translations(): Map<String, String> {
        if (loadedFor == EN) return table
        val ctx = appContext ?: return emptyMap()
        table = runCatching {
            val ios = ctx.assets.open("en.lproj/Localizable.strings").bufferedReader(Charsets.UTF_8).use { parseStrings(it.readText()) }
            // Compléments propres à Android (tous les fichiers .strings de assets/android.lproj) ;
            // les traductions iOS restent prioritaires.
            val android = HashMap<String, String>()
            runCatching { ctx.assets.list("android.lproj").orEmpty() }.getOrElse { emptyArray() }
                .filter { it.endsWith(".strings") }.sorted().forEach { name ->
                    runCatching {
                        ctx.assets.open("android.lproj/$name").bufferedReader(Charsets.UTF_8).use { android.putAll(parseStrings(it.readText())) }
                    }
                }
            android + ios
        }.getOrElse { emptyMap() }
        loadedFor = EN
        return table
    }

    /** Lecture d'un fichier Apple .strings : "clé" = "valeur"; (commentaires ignorés). */
    internal fun parseStrings(text: String): Map<String, String> {
        val out = HashMap<String, String>(1024)
        var i = 0
        val n = text.length
        fun readQuoted(): String? {
            while (i < n && text[i] != '"') {
                if (text.startsWith("/*", i)) { val e = text.indexOf("*/", i + 2); i = if (e < 0) n else e + 2; continue }
                if (text.startsWith("//", i)) { val e = text.indexOf('\n', i); i = if (e < 0) n else e + 1; continue }
                i++
            }
            if (i >= n) return null
            i++
            val sb = StringBuilder()
            while (i < n && text[i] != '"') {
                val c = text[i]
                if (c == '\\' && i + 1 < n) {
                    when (val e = text[i + 1]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); '"' -> sb.append('"'); '\\' -> sb.append('\\')
                        else -> { sb.append('\\'); sb.append(e) }
                    }
                    i += 2
                } else { sb.append(c); i++ }
            }
            i++
            return sb.toString()
        }
        while (i < n) {
            val k = readQuoted() ?: break
            val eq = text.indexOf('=', i)
            if (eq < 0) break
            i = eq + 1
            val v = readQuoted() ?: break
            // Valeurs générées avec double échappement (« \\n ») : on les ramène à de vrais retours à la ligne.
            out[k] = v.replace("\\n", "\n").replace("\\\"", "\"")
            val semi = text.indexOf(';', i)
            i = if (semi < 0) n else semi + 1
        }
        return out
    }
}
