package com.ssafy.e106.features.proposals

import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.proposals.data.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ProposalApiTest {
    private val record = """{"proposalId":10,"status":"WAITING_FOR_OTHER","myDecision":"ACCEPT","createdAt":"2026-11-03T00:00:00Z","expiresAt":"2026-11-04T00:00:00Z","chatAvailable":false,"chatRoomId":null}"""
    private fun client(handler: MockRequestHandler) = HttpClient(MockEngine(handler)) {
        install(ContentNegotiation) { json(ApiJson) }
        defaultRequest { url("https://tingle.test/dev/api/") }
    }
    @Test fun acceptanceRetainsVersionKeyAndDevBasePath() = runTest {
        client { request ->
            assertEquals("/dev/api/match-proposals/10/accept", request.url.encodedPath)
            assertEquals("same-key", request.headers["Idempotency-Key"])
            assertEquals("""{"profileVersion":"${"a".repeat(64)}"}""", request.body.toByteArray().decodeToString())
            respond(record, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }.use { client ->
            val result = HttpProposalRepository(client).decide(PendingDecision(1, 10, "ACCEPT", "same-key", "a".repeat(64)))
            assertIs<ApiResult.Ok<ProposalRecord>>(result)
        }
    }
    @Test fun declineSendsNoProfileOrBody() = runTest {
        client { request ->
            assertEquals("/dev/api/match-proposals/10/decline", request.url.encodedPath)
            assertTrue(request.body.toByteArray().isEmpty())
            respond(record.replace("ACCEPT", "DECLINE"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }.use { HttpProposalRepository(it).decide(PendingDecision(1, 10, "DECLINE", "key", null)) }
    }
    @Test fun cursorIsSentWithoutReinterpretingIt() = runTest {
        client { request ->
            assertEquals("a+/=", request.url.parameters["cursor"])
            assertEquals("20", request.url.parameters["limit"])
            respond("""{"items":[],"nextCursor":null,"serverTime":"now"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }.use { assertIs<ApiResult.Ok<ProposalPage>>(HttpProposalRepository(it).list("a+/=")) }
    }
    @Test fun photoIsBytesNotAUrlAndMimeIsValidated() = runTest {
        client { respond(byteArrayOf(1, 2), headers = headersOf(HttpHeaders.ContentType, "image/jpeg")) }.use {
            assertContentEquals(byteArrayOf(1, 2), (HttpProposalRepository(it).photo(10) as ApiResult.Ok).value)
        }
        client { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }.use {
            assertIs<ApiResult.Fail>(HttpProposalRepository(it).photo(10))
        }
    }
}
