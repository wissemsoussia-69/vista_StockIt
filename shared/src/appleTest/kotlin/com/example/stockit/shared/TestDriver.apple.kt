package com.example.stockit.shared

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.example.stockit.shared.db.StockItDatabase

actual fun createTestDriver(): SqlDriver = inMemoryDriver(StockItDatabase.Schema)

actual fun createEmptyTestDriver(): SqlDriver = NativeSqliteDriver(
    DatabaseConfiguration(
        name = null,
        version = 1,
        create = {},
        upgrade = { _, _, _ -> },
        inMemory = true,
    ),
)
