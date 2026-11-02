package com.ssafy.e106.features.onboarding

import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.onboarding.data.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class OnboardingContractTest {
    @Test fun agreementBodyIncludesDeclinedOptionalAndUsesApiBasePath() = runTest {
        var path = ""; var body = ""
        val client = createHttpClient("https://test/dev/api/", httpLog = false, engine = MockEngine { request ->
            path = request.url.encodedPath; body = request.body.toByteArray().decodeToString()
            respond("{\"onboardingStatus\":\"KEYRING_REQUIRED\"}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            assertIs<ApiResult.Ok<Unit>>(HttpOnboardingRepository(client).agree(7, listOf(AgreementAnswer("TERMS", "v1", true), AgreementAnswer("OPTIONAL", "v2", false))))
            assertEquals("/dev/api/users/7/agreements", path)
            val items = Json.parseToJsonElement(body).jsonObject.getValue("agreements").jsonArray
            assertFalse(items.last().jsonObject.getValue("agreed").jsonPrimitive.boolean)
            assertEquals("v2", items.last().jsonObject.getValue("version").jsonPrimitive.content)
        } finally { client.close() }
    }
}
