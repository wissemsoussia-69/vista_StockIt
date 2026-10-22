package com.example.stockit.shared

import app.cash.sqldelight.db.SqlDriver
import com.example.stockit.shared.db.StockItDatabase
import com.example.stockit.shared.network.StockItApi
import com.example.stockit.shared.session.KeyValueStore
import com.example.stockit.shared.session.SessionStore

/**
 * Point d'entrée du module partagé.
 *
 *  - Android : `AndroidStockIt.setup(context)`
 *  - iOS     : `IosStockIt.shared.setup(deviceName:)` (cf. iosApp/StockIT/App/StockITApp.swift)
 */
object StockIt {
    private var _repository: StockItRepository? = null
    private var _session: SessionStore? = null

    val repository: StockItRepository
        get() = _repository ?: error("StockIt.setup() n'a pas été appelé")

    val session: SessionStore
        get() = _session ?: error("StockIt.setup() n'a pas été appelé")

    internal val sessionOrNull: SessionStore? get() = _session

    val isReady: Boolean get() = _repository != null

    internal fun setup(driver: SqlDriver, kv: KeyValueStore, deviceName: String) {
        if (_repository != null) return
        val db = StockItDatabase(driver)
        _session = SessionStore(kv)
        _repository = StockItRepository(db, deviceName)
    }

    /** Access token Auth0 courant (injecté en Bearer sur le backend réclamations). */
    fun setAccessToken(token: String?) {
        StockItApi.accessTokenProvider = token?.let { t -> suspend { t } }
    }
}
