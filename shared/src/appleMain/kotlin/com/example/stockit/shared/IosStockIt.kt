package com.example.stockit.shared

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseFileContext
import com.example.stockit.shared.db.StockItDatabase
import com.example.stockit.shared.session.KeyValueStore
import com.example.stockit.shared.session.SessionStore
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUnlessOpen
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDefaults

/**
 * Initialisation iOS du module partagé.
 * Swift : `IosStockIt.shared.setup(deviceName: UIDevice.current.model)`
 *
 * Données locales (threat model T-07) :
 *  - session (identité, rôle) en mémoire uniquement — rien dans NSUserDefaults ;
 *  - dossier de la base exclu des sauvegardes iCloud / Finder et protégé
 *    (NSFileProtectionCompleteUnlessOpen : illisible quand l'iPhone est verrouillé, sauf fichier déjà ouvert).
 */
object IosStockIt {
    private const val DB_NAME = "stockit_shared.db"
    private val LEGACY_SESSION_KEYS = listOf("logged_in", "username", "email", "role", "provider")

    fun setup(deviceName: String) {
        val driver = NativeSqliteDriver(StockItDatabase.Schema, DB_NAME)
        protectDatabaseDirectory()
        // Anciennes versions : la session (dont le rôle) était écrite dans la suite « stockit_session ».
        NSUserDefaults(suiteName = SessionStore.PREFS_NAME).let { d -> LEGACY_SESSION_KEYS.forEach { d.removeObjectForKey(it) } }
        StockIt.setup(driver, MemoryStore(), deviceName)
    }

    internal fun databaseDirectory(): String = DatabaseFileContext.databasePath(DB_NAME, null).substringBeforeLast('/')

    @OptIn(ExperimentalForeignApi::class)
    private fun protectDatabaseDirectory() {
        val dir = databaseDirectory()
        val fm = NSFileManager.defaultManager
        val attrs = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUnlessOpen)
        fm.setAttributes(attrs, ofItemAtPath = dir, error = null)
        fm.contentsOfDirectoryAtPath(dir, error = null)?.forEach { name ->
            fm.setAttributes(attrs, ofItemAtPath = "$dir/$name", error = null)
        }
        NSURL.fileURLWithPath(dir, isDirectory = true)
            .setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
    }
}

/** Session en mémoire : disparaît à la fermeture de l'app (qui exige déjà une reconnexion). */
private class MemoryStore : KeyValueStore {
    private val values = mutableMapOf<String, Any>()
    override fun getString(key: String): String? = values[key] as? String
    override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun clear() = values.clear()
}
