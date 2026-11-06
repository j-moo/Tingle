package com.ssafy.e106.features.keyring

import com.ssafy.e106.features.keyring.data.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** 기기 보고를 저장/가입 완료로 승격하거나 미인증 상세를 정상 수치로 보정하지 않는 경계 검사. */
class BleOwnerFlowStatusTest {
    private fun status(json: String) = BleProbeProtocol.status(Json.parseToJsonElement(json).jsonObject)

    @Test fun missingAndNullValuesStayUnknown() {
        listOf("{}", """{"ownerAuthorized":null,"applied":null,"matchingReady":null,"storageTotalBytes":null}""").forEach {
            val flow = status(it).ownerFlow
            assertNull(flow.records); assertNull(flow.applied); assertNull(flow.matchingReady); assertNull(flow.ownerAuthorized)
            assertFalse(flow.readinessConflict)
        }
    }
    @Test fun unauthenticatedOrMalformedAuthorityHidesEvenProvidedCounts() {
        listOf("false", "null", "\"true\"", "1").forEach { auth ->
            val result = status("""{"ownerAuthorized":$auth,"pendingRecordCount":4,"storageTotalBytes":100,"storageUsedBytes":10,"candidateCount":2,"reviewRecordCount":3}""")
            assertNull(result.pendingRecordCount); assertNull(result.ownerFlow.records)
        }
    }
    @Test fun authorizedZeroAndFalseAreNotUnknown() {
        val result = status("""{"ownerAuthorized":true,"pendingRecordCount":0,"candidateCount":0,"reviewRecordCount":0,"unresolvedAccountLockCount":0,"storageTotalBytes":1024,"storageUsedBytes":0,"storageState":"AVAILABLE","applied":false,"timeReady":true,"matchingReady":false}""")
        assertEquals(0L, result.pendingRecordCount); assertEquals(0L, result.ownerFlow.records?.reviewCount)
        assertEquals(0L, result.ownerFlow.records?.usedBytes); assertEquals(false, result.ownerFlow.applied)
        assertEquals(true, result.ownerFlow.timeReady); assertEquals(false, result.ownerFlow.matchingReady)
    }
    @Test fun inconsistentStorageAndInvalidTypesDoNotBecomeHealthyValues() {
        val flow = status("""{"ownerAuthorized":true,"storageTotalBytes":10,"storageUsedBytes":11,"storageState":"NEW_STATE","candidateCount":"3","reviewRecordCount":-1,"unresolvedAccountLockCount":null,"applied":"true","recordCursorEpoch":"not-an-epoch"}""").ownerFlow
        assertNull(flow.records?.totalBytes); assertNull(flow.records?.usedBytes); assertNull(flow.records?.state)
        assertNull(flow.records?.candidateCount); assertNull(flow.records?.reviewCount); assertNull(flow.applied); assertNull(flow.cursorEpoch)
    }
    @Test fun largeVersionsAndCursorArePreservedWithoutNarrowing() {
        val flow = status("""{"storedSettingsVersion":9223372036854775807,"recordCursorEpoch":"55fb638c77fee0b6f5f9275faf297e2c"}""").ownerFlow
        assertEquals(Long.MAX_VALUE, flow.storedSettingsVersion); assertEquals("55fb638c77fee0b6f5f9275faf297e2c", flow.cursorEpoch)
        assertNull(status("""{"storedSettingsVersion":9223372036854775808}""").ownerFlow.storedSettingsVersion)
    }
    @Test fun conflictingReadinessIsFlaggedWithoutRewritingReports() {
        listOf("\"applied\":false", "\"timeReady\":false", "\"ownerAuthorized\":true,\"unresolvedAccountLockCount\":1").forEach { conflict ->
            val flow = status("""{"matchingReady":true,$conflict}""").ownerFlow
            assertTrue(flow.readinessConflict); assertEquals(true, flow.matchingReady)
        }
    }
    @Test fun batteryEstimateAndUnknownPresenceRemainSeparate() {
        val flow = status("""{"batteryPercent":50,"batteryPercentSource":"VOLTAGE_ESTIMATE","batteryPresence":"UNCONFIRMED","powerSource":"USB_ABSENT"}""").ownerFlow
        assertEquals("VOLTAGE_ESTIMATE", flow.batteryPercentSource); assertEquals("UNCONFIRMED", flow.batteryPresence)
        assertEquals("USB_ABSENT", flow.powerSource)
    }
    @Test fun firmwareNameCannotGrantFullSettingsSupport() {
        val info = BleDeviceInfo("serial", "0.7.2-owner-flow", true)
        assertFalse(info.ownerFlow.supportsFullSettings(info))
        val declared = info.copy(ownershipEpoch = 3,
            supportedFunctions = listOf("GET_SYNC_CHALLENGE", "SYNC_SETTINGS"),
            profileStorage = BleProfileStorageInfo(listOf(BleOwnerFlowInfo.FULL_SETTINGS_FORMAT), BleProfileStorageInfo.CHARACTERISTIC, true),
            ownerFlow = BleOwnerFlowInfo("key", "tingle-peer-v3", 1, "tingle-account-lock-v2"))
        assertTrue(declared.ownerFlow.supportsFullSettings(declared))
        assertFalse(declared.ownerFlow.supportsFullSettings(declared.copy(profileStorage = declared.profileStorage.copy(requiresEncryption = false))))
        assertFalse(declared.ownerFlow.supportsFullSettings(declared.copy(ownershipEpoch = null)))
        assertFalse(declared.ownerFlow.copy(peerOwnerScopeVersion = 2).supportsFullSettings(declared))
    }
    @Test fun deviceInfoParsesNewDeclarationsWithoutChangingProofContract() {
        val info = BleProbeProtocol.info(Json.parseToJsonElement("""{"deviceSerial":"test","firmwareVersion":"0.7.2-owner-flow","keyId":"key-1","peerExchangeProtocol":"tingle-peer-v3","peerOwnerScopeVersion":1,"accountLockProofVersion":"tingle-account-lock-v2"}""").jsonObject)
        assertEquals("key-1", info.ownerFlow.keyId); assertEquals(1L, info.ownerFlow.peerOwnerScopeVersion)
        assertEquals(listOf("tingle-device-proof-v1"), info.supportedProofProtocolVersions)
        assertNull(info.ownershipEpoch)
    }
}
