package com.example.stockit.shared

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Threat model T-07 — what the app leaves on the iPhone. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosStorageTest {

    @Test
    fun sessionIdentityAndRoleAreNotWrittenToUserDefaults() {
        IosStockIt.setup("test-device")
        StockIt.session.saveSsoSession("alice", "alice@example.com", "ADMIN")
        val d = NSUserDefaults(suiteName = "stockit_session")
        assertNull(d.stringForKey("role"), "role must not be editable in a plist")
        assertNull(d.stringForKey("username"))
        assertNull(d.stringForKey("email"))
        assertEquals("ADMIN", StockIt.session.role, "the in-memory session still works")
        StockIt.session.clear()
    }

    @Test
    fun databaseDirectoryIsExcludedFromBackups() {
        IosStockIt.setup("test-device")
        val url = NSURL.fileURLWithPath(IosStockIt.databaseDirectory(), isDirectory = true)
        // The flag persists on the simulator between runs: clear it so the test proves what setup() does.
        url.setResourceValue(NSNumber(bool = false), forKey = NSURLIsExcludedFromBackupKey, error = null)
        IosStockIt.setup("test-device")
        val excluded = memScoped {
            val value = alloc<ObjCObjectVar<Any?>>()
            url.getResourceValue(value.ptr, NSURLIsExcludedFromBackupKey, null)
            (value.value as? NSNumber)?.boolValue
        }
        assertEquals(true, excluded, "inventory, customer addresses and audit data must not go to iCloud/iTunes backups")
    }
}
