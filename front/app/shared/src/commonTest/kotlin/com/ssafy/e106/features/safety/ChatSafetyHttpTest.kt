package com.ssafy.e106.features.safety

import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.safety.data.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ChatSafetyHttpTest {
    @Test fun blockUsesRoomContextAndExactReceipt() = runTest {
        val client = createHttpClient("https://example.test/dev/api/",false,MockEngine { req ->
            assertEquals("/dev/api/blocks",req.url.encodedPath); assertEquals("key",req.headers["Idempotency-Key"])
            assertEquals("{\"chatRoomId\":20}",(req.body as TextContent).text)
            respond("""{"proposalId":10,"blockedAt":"2026-11-03T00:00:00Z"}""",HttpStatusCode.Created,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        assertIs<ApiResult.Ok<*>>(HttpSafetyRepository(client).execute(PendingSafety(1,20,"BLOCK","key"))); client.close()
    }
    @Test fun leaveSendsNoJsonBody() = runTest {
        val client = createHttpClient("https://example.test/api/",false,MockEngine { req ->
            assertEquals("/api/chat-rooms/20/leave",req.url.encodedPath); assertIs<OutgoingContent.NoContent>(req.body)
            respond("""{"chatRoomId":20,"leftAt":"2026-11-03T00:00:00Z","rematchingRestricted":true}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        assertIs<ApiResult.Ok<*>>(HttpSafetyRepository(client).execute(PendingSafety(1,20,"LEAVE","key"))); client.close()
    }
    @Test fun icebreakersAcceptPluralKindAndMinimumOnePollInterval() = runTest {
        val client = createHttpClient("https://example.test/api/",false,MockEngine { req ->
            assertEquals("/api/chat-rooms/20/icebreakers",req.url.encodedPath)
            respond("""{"chatRoomId":20,"kind":"ICEBREAKERS","status":"SUCCEEDED","statusUrl":"/api/chat-rooms/20/icebreakers","errorCode":null,"retryable":false,"pollAfterSeconds":1}""",HttpStatusCode.Accepted,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val result = assertIs<ApiResult.Ok<IcebreakerTask>>(HttpChatExtrasRepository(client).requestQuestions(20,"key"))
        assertEquals(1,result.value.pollAfterSeconds); client.close()
    }
}
