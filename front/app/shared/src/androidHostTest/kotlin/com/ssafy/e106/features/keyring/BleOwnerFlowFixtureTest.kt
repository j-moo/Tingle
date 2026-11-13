package com.ssafy.e106.features.keyring

import com.ssafy.e106.features.keyring.data.*
import java.security.MessageDigest
import kotlin.test.*

/** IoT가 보존한 실제 C 출력 fixture를 다시 만들어 쓰지 않고 그대로 소비한다. 합성 데이터이며 실물 통과가 아니다. */
class BleOwnerFlowFixtureTest {
    @Test fun cStatusFixtureMatchesIotContractHash() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/keyring/status-0.7.json")).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        // iot/tests/data/status-0.7.json 과 같은 bytes. iot verify-app-flow.mjs 가 같은 해시를 고정한다.
        assertEquals("e8809676ee5f40cd75c0769143ccbef19659c20d5e87b436a18f0f4811894bcd", hash)
    }

    @Test fun cStatusFixtureIncludesStorageAndIndependentReadiness() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/keyring/status-0.7.json")).use { it.readBytes() }
        val status = BleProbeProtocol.status(BleProbeProtocol.response(bytes, "test", "STATUS"))
        assertEquals(262144L, status.ownerFlow.records?.totalBytes)
        assertEquals(12000L, status.ownerFlow.records?.usedBytes)
        assertEquals(0L, status.ownerFlow.records?.unresolvedAccountLockCount)
        assertEquals(true, status.ownerFlow.applied); assertEquals(true, status.ownerFlow.timeReady)
        assertEquals(true, status.ownerFlow.matchingReady); assertFalse(status.ownerFlow.readinessConflict)
        assertEquals("UNCONFIRMED", status.ownerFlow.batteryPresence)
    }
}
