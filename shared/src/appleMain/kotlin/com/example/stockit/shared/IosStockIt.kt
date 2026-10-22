package com.example.stockit.shared

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.example.stockit.shared.db.StockItDatabase
import com.example.stockit.shared.session.KeyValueStore
import com.example.stockit.shared.session.SessionStore
import platform.Foundation.NSUserDefaults

/**
 * Initialisation iOS du module partagé.
 * Swift : `IosStockIt.shared.setup(deviceName: UIDevice.current.model)`
 */
object IosStockIt {
    fun setup(deviceName: String) {
        val driver = NativeSqliteDriver(StockItDatabase.Schema, "stockit_shared.db")
        val defaults = NSUserDefaults(suiteName = SessionStore.PREFS_NAME)
        StockIt.setup(driver, DefaultsStore(defaults), deviceName)
    }
}

private class DefaultsStore(private val d: NSUserDefaults) : KeyValueStore {
    override fun getString(key: String): String? = d.stringForKey(key)
    override fun putString(key: String, value: String?) {
        if (value == null) d.removeObjectForKey(key) else d.setObject(value, forKey = key)
    }
    override fun getBoolean(key: String, default: Boolean): Boolean =
        if (d.objectForKey(key) == null) default else d.boolForKey(key)
    override fun putBoolean(key: String, value: Boolean) = d.setBool(value, forKey = key)
    override fun clear() {
        listOf("logged_in", "username", "email", "role", "provider").forEach { d.removeObjectForKey(it) }
    }
}
