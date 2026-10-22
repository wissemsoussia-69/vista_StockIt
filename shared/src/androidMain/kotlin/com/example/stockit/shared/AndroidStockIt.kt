package com.example.stockit.shared

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.example.stockit.shared.db.StockItDatabase
import com.example.stockit.shared.session.KeyValueStore
import com.example.stockit.shared.session.SessionStore

/** Initialisation Android du module partagé. */
object AndroidStockIt {
    fun setup(context: Context) {
        val app = context.applicationContext
        val driver = AndroidSqliteDriver(StockItDatabase.Schema, app, "stockit_shared.db")
        val prefs = app.getSharedPreferences(SessionStore.PREFS_NAME, Context.MODE_PRIVATE)
        StockIt.setup(driver, PrefsStore(prefs), "${Build.MANUFACTURER} ${Build.MODEL}")
    }
}

private class PrefsStore(private val p: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = p.getString(key, null)
    override fun putString(key: String, value: String?) {
        p.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
    override fun getBoolean(key: String, default: Boolean) = p.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { p.edit().putBoolean(key, value).apply() }
    override fun clear() { p.edit().clear().apply() }
}
