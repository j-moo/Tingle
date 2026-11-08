package com.ssafy.e106.core.storage

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

/** 실제 Keystore 암호화를 검사한다. test.invalid 전용 임시 파일만 사용하며 실제 앱의 영수증에는 접근하지 않는다. */
class ProtectedRecordDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private suspend fun fixture(block: suspend (AndroidProtectedRecordStore, String) -> Unit) {
        val base = "https://test.invalid/" + java.util.UUID.randomUUID() + "/"
        val store = AndroidProtectedRecordStore(context, base)
        try { block(store, base) } finally { store.directory.deleteRecursively() }
    }

    @Test fun encryptedRecordSurvivesRecreationAndTokenCleanup() = runTest { fixture { store, base ->
        store.write("withdrawal.75", "RECEIPT-SENTINEL")
        assertTrue(store.directory.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertFalse(store.directory.listFiles()!!.any { it.readBytes().decodeToString().contains("RECEIPT-SENTINEL") })
        AndroidTokenStore(context, base).clear()
        assertEquals("RECEIPT-SENTINEL", assertIs<ProtectedRead.Found>(AndroidProtectedRecordStore(context, base).read("withdrawal.75")).value)
    } }

    @Test fun serversAndAccountsDoNotShareRecords() = runTest { fixture { store, base ->
        store.write("withdrawal.75", "one")
        assertEquals(ProtectedRead.Missing, store.read("withdrawal.7"))
        assertEquals(ProtectedRead.Missing, AndroidProtectedRecordStore(context, base + "other").read("withdrawal.75"))
    } }

    @Test fun tamperedFileIsUnavailableAndNotOverwritten() = runTest { fixture { store, _ ->
        store.write("withdrawal.75", "original")
        val file = store.directory.listFiles()!!.single()
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(ProtectedRead.Unavailable, store.read("withdrawal.75"))
        assertFailsWith<IllegalStateException> { store.write("withdrawal.75", "new") }
        assertContentEquals(byteArrayOf(1, 2, 3), file.readBytes())
    } }

    @Test fun oldCompletionCannotRemoveNewRecord() = runTest { fixture { store, _ ->
        store.write("withdrawal.75", "old"); store.write("withdrawal.75", "new")
        assertFalse(store.remove("withdrawal.75", "old"))
        assertEquals("new", assertIs<ProtectedRead.Found>(store.read("withdrawal.75")).value)
        assertTrue(store.remove("withdrawal.75", "new"))
        assertEquals(ProtectedRead.Missing, store.read("withdrawal.75"))
    } }
}
