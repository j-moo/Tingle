package com.ssafy.e106.features.settings

import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.settings.data.*
import com.ssafy.e106.features.settings.ui.ConflictValues
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SettingsApiTest {
    @Test fun anySendsExplicitNullKeysAndStrongEtagWithoutGender() = runTest {
        var payload = ""; var tag: String? = null; var path = ""
        val client = createHttpClient("https://test/dev/api/", httpLog = false, engine = MockEngine { request ->
            payload = request.body.toByteArray().decodeToString(); tag = request.headers[HttpHeaders.IfMatch]
            path = request.url.encodedPath
            respond("""{"desiredGenders":["FEMALE"],"desiredAgeMode":"ANY","desiredAgeMin":null,"desiredAgeMax":null}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"next\"")))
        })
        try {
            val result = HttpMatchingSettingsRepository(client).save(7, SettingsKind.AGE, ConflictValues.Age(any = true), "\"old\"")
            assertEquals("/dev/api/users/7/preferences", path); assertEquals("\"old\"", tag)
            assertEquals(Json.parseToJsonElement("""{"desiredAgeMode":"ANY","desiredAgeMin":null,"desiredAgeMax":null}"""), Json.parseToJsonElement(payload))
            assertEquals("\"next\"", assertIs<ApiResult.Ok<SettingsDocument>>(result).value.etag)
        } finally { client.close() }
    }
    @Test fun matchingPutContainsOnlyTwoBooleansAndRetainsResponseVersion() = runTest {
        val client = createHttpClient("https://test/dev/api/", httpLog = false, engine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("/dev/api/users/3/settings", request.url.encodedPath)
            assertEquals("\"2\"", request.headers[HttpHeaders.IfMatch])
            assertEquals(Json.parseToJsonElement("""{"matchingEnabled":false,"requirePartnerPhoto":true}"""),
                Json.parseToJsonElement(request.body.toByteArray().decodeToString()))
            respond("""{"matchingEnabled":false,"requirePartnerPhoto":true,"settingsVersion":3}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"3\"")))
        })
        try { assertEquals(3, assertIs<ApiResult.Ok<SettingsDocument>>(HttpMatchingSettingsRepository(client)
            .save(3, SettingsKind.MATCHING, ConflictValues.Matching(false, true), "\"2\"")).value.settingsVersion) }
        finally { client.close() }
    }
    @Test fun missingModeIsNotSilentlyInterpretedAsAny() = runTest {
        val client = createHttpClient("https://test/dev/api/", httpLog = false, engine = MockEngine {
            respond("""{"desiredGenders":["MALE"],"desiredAgeMode":null,"desiredAgeMin":null,"desiredAgeMax":null}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val doc = assertIs<ApiResult.Ok<SettingsDocument>>(HttpMatchingSettingsRepository(client).get(1, SettingsKind.AGE)).value
            assertTrue(assertIs<ConflictValues.Age>(doc.values).unselected); assertNull(doc.etag)
        } finally { client.close() }
    }
    @Test fun onboardingFailureIsPreserved() = runTest {
        val client = createHttpClient("https://test/dev/api/", httpLog = false, engine = MockEngine {
            respond("""{"code":"ONBOARDING_REQUIRED","message":"blocked"}""", HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try { assertIs<AppError.OnboardingRequired>(assertIs<ApiResult.Fail>(HttpMatchingSettingsRepository(client).get(1, SettingsKind.MATCHING)).error) }
        finally { client.close() }
    }
}
