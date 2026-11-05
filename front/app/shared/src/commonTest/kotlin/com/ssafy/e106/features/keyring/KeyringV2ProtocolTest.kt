package com.ssafy.e106.features.keyring

import com.ssafy.e106.core.network.ApiJson
import com.ssafy.e106.features.keyring.data.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** BE DTO·IoT request.c의 실제 필드 조합을 대조한다. 서명의 진위는 이 검사에서 검증한 것으로 취급하지 않는다. */
class KeyringV2ProtocolTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val epoch = 9007199254740993L
    private val challenge = KeyringChallenge(id, "A".repeat(43), "2026-11-06T00:00:00Z", "tingle-device-proof-v2")
    private val proof = KeyringProof(challenge.proofProtocolVersion, "key-1", "A".repeat(86))
    private fun registration(version: String = proof.protocolVersion, epochValue: JsonElement? = JsonPrimitive(epoch), owner: String = "UNOWNED") =
        buildJsonObject {
            put("protocolVersion", 1); put("type", "REGISTRATION_PROOF"); put("requestId", id)
            put("deviceSerial", "XIAO-test"); put("proof", ApiJson.encodeToJsonElement(proof.copy(protocolVersion = version)))
            epochValue?.let { put("ownershipEpoch", it) }; put("ownerState", owner)
        }.toString().encodeToByteArray()
    private fun run() = KeyringSyncRun(id, id, 4, "22222222-2222-4222-8222-222222222222", Long.MAX_VALUE,
        Long.MAX_VALUE, "PENDING", false, "AQ", "base64url", "tingle-dev-settings-v1", "a".repeat(64),
        "A".repeat(43), "key-1", proof.protocolVersion, "2026-11-05T12:00:00Z", null, "2026-11-05T12:00:01Z",
        "tingle-dev-settings-proof-v2", "dev-ed25519-799e2999c648fda3", "A".repeat(43), "A".repeat(86), epoch)

    @Test fun registrationCarriesExactInt64AndUnownedStateToServer() {
        assertTrue(KeyringRegistrationProtocol.validChallenge(challenge))
        val body = KeyringRegistrationProtocol.response(registration(), id, "XIAO-test", challenge)
        val json = ApiJson.encodeToJsonElement(body).jsonObject
        assertEquals(epoch, json.getValue("ownershipEpoch").jsonPrimitive.long)
        assertFalse(json.getValue("ownershipEpoch").jsonPrimitive.isString)
        assertEquals("UNOWNED", json.getValue("ownerState").jsonPrimitive.content)
        val request = KeyringRegistrationProtocol.request(challenge, id).decodeToString()
        assertTrue(request.endsWith("\n")); assertEquals(JsonPrimitive(1), Json.parseToJsonElement(request).jsonObject["protocolVersion"])
    }

    @Test fun registrationRejectsDowngradeMissingOrMalformedEpochAndOwnedDevice() {
        for (bytes in listOf(registration(version = "tingle-device-proof-v1"), registration(epochValue = null),
            registration(epochValue = JsonPrimitive(-1)), registration(epochValue = JsonPrimitive("1")),
            registration(epochValue = Json.parseToJsonElement("9223372036854775808")), registration(owner = "BOUND"))) {
            assertFails { KeyringRegistrationProtocol.response(bytes, id, "XIAO-test", challenge) }
        }
    }

    @Test fun syncKeepsEnvelopeAndPayloadV1ButTransmitsEpochAndProofV2() {
        val run = run()
        val bytes = KeyringSyncProtocol.request(run, id)
        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals(epoch, json.getValue("ownershipEpoch").jsonPrimitive.long)
        assertEquals(Long.MAX_VALUE, json.getValue("settingsVersion").jsonPrimitive.long)
        assertEquals(JsonPrimitive(1), json["protocolVersion"])
        assertEquals(JsonPrimitive("tingle-dev-settings-v1"), json["payloadFormat"])
        assertEquals(JsonPrimitive("AQ"), json["payload"])
        assertEquals(JsonPrimitive(run.serverSignature), json["serverSignature"])
        assertEquals(10.toByte(), bytes.last())
        val response = buildJsonObject {
            put("protocolVersion", 1); put("type", "SETTINGS_RESULT"); put("requestId", id); put("syncRunId", id)
            put("settingsVersion", run.settingsVersion); put("payloadHash", run.payloadHash)
            put("stored", true); put("applied", false); put("timeReady", false)
            put("proof", ApiJson.encodeToJsonElement(proof))
        }
        val result = KeyringSyncProtocol.response(response.toString().encodeToByteArray(), id, run)
        assertEquals(proof, result.ack.proof); assertFalse(result.applied); assertFalse(result.timeReady)
        assertEquals(setOf("settingsVersion", "payloadHash", "proof"), ApiJson.encodeToJsonElement(result.ack).jsonObject.keys)
    }

    @Test fun mixedProofVersionsAndEpochsNeverProduceSettingsCommand() {
        val run = run()
        for (bad in listOf(run.copy(ownershipEpoch = null), run.copy(ownershipEpoch = -1),
            run.copy(proofProtocolVersion = "tingle-device-proof-v1"),
            run.copy(serverProofProtocolVersion = "tingle-dev-settings-proof-v1"))) {
            assertFails { KeyringSyncProtocol.request(bad, id) }
        }
        assertNotEquals(run.identity(), run.copy(ownershipEpoch = epoch + 1).identity())
        // 구형 복구 JSON에는 신규 필드가 없다. v1 기본값을 유지해 정상 복구를 막지 않는다.
        val v1 = run.copy(proofProtocolVersion = "tingle-device-proof-v1", serverProofProtocolVersion = "tingle-dev-settings-proof-v1", ownershipEpoch = null)
        val old = ApiJson.encodeToJsonElement(v1.identity()).jsonObject.filterKeys { it !in setOf("ownershipEpoch", "proofProtocolVersion") }
        assertEquals(v1.identity(), ApiJson.decodeFromJsonElement<SyncIdentity>(JsonObject(old)))
        assertFalse(Json.parseToJsonElement(KeyringSyncProtocol.request(v1, id).decodeToString()).jsonObject.containsKey("ownershipEpoch"))
    }

    @Test fun deviceCapabilitiesRejectOldFirmwareAndUnknownOrResettingOwner() {
        fun info(extra: String = "") = BleProbeProtocol.info(Json.parseToJsonElement(
            """{"deviceSerial":"XIAO-test","firmwareVersion":"test","supportedFunctions":["GET_STATUS","GET_REGISTRATION_PROOF"]$extra}""").jsonObject)
        val old = info()
        assertTrue(old.supportsProof("tingle-device-proof-v1")); assertFalse(old.supportsProof(proof.protocolVersion))
        val v2 = info(""", "ownershipEpoch":$epoch,"ownerState":"UNOWNED","supportedProofProtocolVersions":["tingle-device-proof-v2"]""")
        assertEquals(epoch, v2.ownershipEpoch); assertTrue(v2.supportsProof(proof.protocolVersion))
        assertFalse(v2.supportsProof("tingle-device-proof-v1"))
        assertFalse(v2.copy(ownerState = "RESETTING").supportsProof(proof.protocolVersion))
        assertFalse(v2.copy(ownerState = "UNKNOWN").supportsProof(proof.protocolVersion))
        for (bad in listOf(""", "ownershipEpoch":"0"""", """, "ownershipEpoch":-1""", """, "supportedProofProtocolVersions":1""")) {
            assertFails { info(bad) }
        }
    }
}
