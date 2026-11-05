package com.ssafy.e106.features.keyring

import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.keyring.data.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** 최신 BE 공개 계약과 IoT 증명 결속을 검사한다. 실제 서명 검증은 서버/펌웨어의 책임이다. */
class KeyringResetContractTest {
    private fun fields() = buildJsonObject {
        put("protocolVersion", 1); put("type", "RESET_DEVICE"); put("requestId", resetId)
        put("resetProtocolVersion", KeyringResetProtocol.VERSION); put("deviceSerial", resetSerial)
        put("deviceId", resetRun.deviceId); put("ownerGeneration", resetGeneration); put("resetRunId", resetId)
        put("fromOwnershipEpoch", 9); put("toOwnershipEpoch", 10); put("resetNonce", resetRun.resetNonce)
        put("deletionScope", "OWNER_DATA_V1"); put("recordPolicy", "REQUIRE_SYNCED")
        put("keyId", "key-1"); put("serverKeyId", "server-key"); put("serverSignature", "A".repeat(86))
        put("challengeId", resetId); put("deviceNonce", resetChallenge.deviceNonce)
    }
    private fun result() = JsonObject(fields() + mapOf("type" to JsonPrimitive("RESET_RESULT"),
        "state" to JsonPrimitive("RESET_DONE"), "result" to JsonPrimitive("CLEARED"), "dataErasure" to JsonPrimitive("LOGICAL"),
        "proof" to buildJsonObject { put("protocolVersion", KeyringResetProtocol.VERSION); put("keyId", "key-1"); put("signature", resetProof.proof) }))

    @Test fun publicPathsHeadersAndStringProofAreExact() = runTest {
        val bodies = mutableListOf<JsonObject>(); var calls = 0
        val paths = listOf("", "/$resetId", "/$resetId/command", "/$resetId/ack")
        val raw = "  ${fields()}\n"
        val client = createHttpClient("https://test/dev/api/", httpLog = false, engine = MockEngine { request ->
            assertEquals("/dev/api/keyrings/${resetRun.deviceId}/reset-runs${paths[calls]}", request.url.encodedPath)
            assertEquals(if (calls == 1) HttpMethod.Get else HttpMethod.Post, request.method)
            assertEquals(if (calls == 0) resetId else null, request.headers["Idempotency-Key"])
            if (calls == 1) assertEquals("no-store", request.headers[HttpHeaders.CacheControl])
            else bodies += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            calls++
            respond(ApiJson.encodeToString(KeyringResetRun.serializer(), resetRun.copy(deviceCommand = raw)),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = HttpKeyringResetRepository(client)
            val run = assertIs<ApiResult.Ok<KeyringResetRun>>(api.start(resetRun.deviceId, resetId, ResetStartBody(false, resetChallenge))).value
            assertEquals(raw, run.deviceCommand)
            api.get(resetRun.deviceId, resetId); api.command(resetRun.deviceId, resetId, ResetCommandBody(resetChallenge))
            api.ack(resetRun.deviceId, resetId, resetProof)
            assertEquals(setOf("discardUnsentConfirmed", "deviceChallenge"), bodies[0].keys)
            assertEquals(setOf("deviceChallenge"), bodies[1].keys)
            assertEquals(buildJsonObject { put("proof", resetProof.proof) }, bodies[2])
        } finally { client.close() }
    }
    @Test fun commandPreservesWhitespaceLfAnd64BitIdentifiers() {
        val raw = "  ${fields()}  \n"
        assertContentEquals(raw.encodeToByteArray(), KeyringResetProtocol.command(resetRun.copy(deviceCommand = raw), resetSerial, "key-1"))
        for (invalid in listOf(raw.trimEnd(), raw + "\n", raw.replace("\n", "\r\n"), raw.replace("OWNER_DATA_V1", "ALL"),
            raw.replace("9007199254740993", "9007199254740992"))) {
            assertFailsWith<IllegalArgumentException> { KeyringResetProtocol.command(resetRun.copy(deviceCommand = invalid), resetSerial, "key-1") }
        }
    }
    @Test fun completionRequiresEveryBindingAndProofKey() {
        assertEquals(resetProof, KeyringResetProtocol.result(result().toString().encodeToByteArray(), resetId, resetRun, resetSerial, "key-1"))
        val alterations = mapOf("deviceSerial" to JsonPrimitive("other"), "deviceId" to JsonPrimitive(4),
            "ownerGeneration" to JsonPrimitive(resetId), "resetRunId" to JsonPrimitive(resetGeneration),
            "fromOwnershipEpoch" to JsonPrimitive(8), "toOwnershipEpoch" to JsonPrimitive(11),
            "resetNonce" to JsonPrimitive("B".repeat(43)), "deletionScope" to JsonPrimitive("ALL"),
            "recordPolicy" to JsonPrimitive("DISCARD_WITH_CONSENT"), "dataErasure" to JsonPrimitive("NONE"))
        for ((key, value) in alterations) assertFailsWith<IllegalArgumentException> {
            KeyringResetProtocol.result(JsonObject(result() + (key to value)).toString().encodeToByteArray(), resetId, resetRun, resetSerial, "key-1")
        }
        assertFailsWith<IllegalArgumentException> {
            KeyringResetProtocol.result(result().toString().encodeToByteArray(), resetId, resetRun, resetSerial, "wrong-key")
        }
    }
    @Test fun pendingIsNotProofAndChallengeRequiresMatchingEpochAndEncoding() {
        val pending = buildJsonObject { put("protocolVersion", 1); put("type", "RESET_RESULT"); put("requestId", resetId)
            put("resetRunId", resetId); put("state", "RESETTING"); put("result", "PENDING") }
        assertNull(KeyringResetProtocol.result(pending.toString().encodeToByteArray(), resetId, resetRun, resetSerial, "key-1"))
        assertTrue(KeyringResetProtocol.challenge(resetChallenge))
        assertFalse(KeyringResetProtocol.challenge(resetChallenge.copy(ownershipEpoch = Long.MAX_VALUE)))
        assertFalse(KeyringResetProtocol.challenge(resetChallenge.copy(deviceNonce = "A".repeat(42) + "B")))
        assertFalse(KeyringResetProtocol.challenge(resetChallenge.copy(expiresInSeconds = 0)))
    }
}
