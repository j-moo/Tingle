package com.ssafy.e106.features.keyring

import com.ssafy.e106.core.ble.BleException
import com.ssafy.e106.features.keyring.data.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** IoT의 현재 DEVICE_INFO wire 필드만 검사한다. 가상 데이터로 실제 저장/암호화 성공을 주장하지 않는다. */
class BleProfileCapabilityTest {
    private val base = buildJsonObject {
        put("deviceSerial", "test-keyring"); put("firmwareVersion", "0.4.0-dev-peer")
        putJsonArray("supportedFunctions") { add("GET_SYNC_CHALLENGE"); add("SYNC_SETTINGS") }
    }
    private val supported = buildJsonObject {
        base.forEach { (key, value) -> put(key, value) }
        putJsonArray("supportedPayloadFormats") { add("tingle-dev-settings-v1"); add(BleProfileStorageInfo.FORMAT) }
        put("profileStorageCharacteristic", BleProfileStorageInfo.CHARACTERISTIC)
        put("profileRequiresEncryption", true)
        put("storedPayloadFormat", BleProfileStorageInfo.FORMAT)
        put("profileStored", true); put("storedPayloadBytes", 196)
    }
    private fun parse(value: JsonObject) = BleProbeProtocol.info(value)
    private fun changed(key: String, value: JsonElement) = JsonObject(supported + (key to value))

    @Test fun oldDeviceAndVersionNameDoNotImplyProfileSupport() {
        val info = parse(base)
        assertFalse(info.supportsProtectedProfileStorage)
        assertNull(info.profileStorage.supportedFormats)
        assertNull(info.profileStorage.reportsProfileStored)
    }

    @Test fun exactCapabilityAndConsistentStorageAreReported() {
        val info = parse(supported)
        assertTrue(info.supportsProtectedProfileStorage)
        assertEquals(true, info.profileStorage.reportsProfileStored)
        assertEquals(196L, info.profileStorage.storedBytes)
        assertTrue(parse(changed("profileStorageCharacteristic", JsonPrimitive(BleProfileStorageInfo.CHARACTERISTIC.uppercase())))
            .supportsProtectedProfileStorage)
    }

    @Test fun everyProtectionRequirementMustBePresent() {
        for (key in listOf("supportedPayloadFormats", "profileStorageCharacteristic", "profileRequiresEncryption", "supportedFunctions")) {
            assertFalse(parse(JsonObject(supported - key)).supportsProtectedProfileStorage, key)
        }
        assertFalse(parse(changed("profileRequiresEncryption", JsonPrimitive(false))).supportsProtectedProfileStorage)
        assertFalse(parse(changed("profileStorageCharacteristic", JsonPrimitive("6e400002-b5a3-f393-e0a9-e50e24dcca9e"))).supportsProtectedProfileStorage)
        assertFalse(parse(changed("supportedPayloadFormats", JsonArray(listOf(JsonPrimitive("future-format"))))).supportsProtectedProfileStorage)
    }

    @Test fun malformedFieldsAreNotSilentlyCoerced() {
        val cases = listOf("profileRequiresEncryption" to JsonPrimitive("true"), "profileStored" to JsonPrimitive(1),
            "storedPayloadBytes" to JsonPrimitive("196"), "storedPayloadBytes" to JsonPrimitive(-1),
            "storedPayloadBytes" to JsonPrimitive(2049), "supportedPayloadFormats" to JsonPrimitive("format"),
            "supportedPayloadFormats" to JsonArray(listOf(JsonPrimitive(1))), "profileStorageCharacteristic" to JsonNull)
        cases.forEach { (key, value) -> assertFailsWith<BleException>(key) { parse(changed(key, value)) } }
    }

    @Test fun storedFlagAloneOrContradictionDoesNotClaimStored() {
        for (key in listOf("storedPayloadFormat", "profileStored", "storedPayloadBytes")) {
            assertNull(parse(JsonObject(supported - key)).profileStorage.reportsProfileStored)
        }
        assertNull(parse(changed("storedPayloadBytes", JsonPrimitive(1))).profileStorage.reportsProfileStored)
        assertNull(parse(changed("profileStored", JsonPrimitive(false))).profileStorage.reportsProfileStored)
        assertNull(parse(changed("storedPayloadFormat", JsonPrimitive("tingle-dev-settings-v1"))).profileStorage.reportsProfileStored)
    }

    @Test fun emptyAndLegacyStoredPayloadRemainDifferentFromProfile() {
        for ((format, bytes) in listOf("" to 0, "tingle-dev-settings-v1" to 1)) {
            val info = parse(JsonObject(supported + mapOf("storedPayloadFormat" to JsonPrimitive(format),
                "storedPayloadBytes" to JsonPrimitive(bytes), "profileStored" to JsonPrimitive(false))))
            assertEquals(false, info.profileStorage.reportsProfileStored)
            assertTrue(info.supportsProtectedProfileStorage)
        }
    }
}
