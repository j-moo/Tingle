package com.ssafy.e106.features.chat

import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.chat.data.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ChatHttpTest {
    @Test fun sendUsesDevelopmentBaseAndExactIds() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/dev/api/chat-rooms/20/messages", req.url.encodedPath)
            assertEquals(HttpMethod.Post, req.method); assertEquals("key", req.headers["Idempotency-Key"])
            assertEquals("{\"clientMessageId\":\"client\",\"text\":\"본문\"}", (req.body as TextContent).text)
            respond("""{"messageId":1,"clientMessageId":"client","sender":"ME","sequence":1,"text":"본문","createdAt":"2026-11-03T00:00:00Z"}""",
                HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = createHttpClient("https://example.test/dev/api/", false, engine)
        assertIs<ApiResult.Ok<*>>(HttpChatRepository(client).send(20, "key", ChatSendBody("client", "본문"))); client.close()
    }
    @Test fun forwardCursorIsOpaqueAndNeverMixedWithPosition() = runTest {
        val engine = MockEngine { req ->
            assertEquals("opaque+/=", req.url.parameters["cursor"]); assertNull(req.url.parameters["afterSequence"])
            respond("""{"items":[],"nextCursor":null,"serverTime":"now"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = createHttpClient("https://example.test/api/", false, engine)
        val api = HttpChatRepository(client)
        assertIs<ApiResult.Ok<*>>(api.messages(20, "opaque+/="))
        assertIs<ApiResult.Fail>(api.messages(20, "opaque+/=", 1))
        client.close()
    }
    @Test fun stageErrorIsNotAnEmptySuccess() = runTest {
        val client = createHttpClient("https://example.test/api/", false, MockEngine {
            respond("""{"code":"ONBOARDING_REQUIRED","message":"가입 필요"}""", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = assertIs<ApiResult.Fail>(HttpChatRepository(client).list())
        assertIs<AppError.OnboardingRequired>(result.error); client.close()
    }
}
