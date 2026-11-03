package com.ssafy.e106.features.profile

import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.profile.data.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class OptionalProfileContractTest {
    @Test fun consentPathsAndPayloadKeepCurrentVersionAndFalse() = runTest {
        val requests = mutableListOf<Triple<String, String, String>>()
        val client = createHttpClient("https://test/dev/api/", false, engine = MockEngine { r ->
            requests += Triple(r.method.value, r.url.encodedPath, r.body.toByteArray().decodeToString())
            val record = """{"type":"PROFILE_INTRODUCTION","version":"v2","agreed":false,"agreedVersion":"v1","recordedAt":"2026-11-02T01:00:00Z"}"""
            respond(if (r.method == HttpMethod.Get) """{"consents":[$record]}""" else record,
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val repo = HttpOptionalProfileRepository(client)
            assertIs<ApiResult.Ok<OptionalConsentsResponse>>(repo.consents(7))
            assertIs<ApiResult.Ok<OptionalConsentRecord>>(repo.consent(7, OptionalConsentType.PROFILE_INTRODUCTION, OptionalConsentRequest(false, "v2")))
            assertEquals("/dev/api/users/7/optional-consents", requests[0].second)
            assertEquals("PUT", requests[1].first)
            assertEquals("/dev/api/users/7/optional-consents/PROFILE_INTRODUCTION", requests[1].second)
            val body = Json.parseToJsonElement(requests[1].third).jsonObject
            assertEquals(setOf("agreed", "version"), body.keys); assertEquals(JsonPrimitive(false), body["agreed"])
            assertEquals(JsonPrimitive("v2"), body["version"])
        } finally { client.close() }
    }
    @Test fun photoUsesBinaryMultipartAndAuthenticatedClientPathsWithoutJsonEncoding() = runTest {
        val jpeg = byteArrayOf(-1, -40, 1, 2, -1, -39)
        val paths = mutableListOf<Pair<String, String>>()
        val client = createHttpClient("https://test/dev/api/", false, engine = MockEngine { r ->
            paths += r.method.value to r.url.encodedPath
            when(r.method) {
                HttpMethod.Post -> {
                    assertTrue(r.body.contentType.toString().startsWith("multipart/form-data; boundary="))
                    val body = r.body.toByteArray()
                    val text = body.decodeToString()
                    assertTrue(text.contains("name=file") || text.contains("name=\"file\""))
                    assertTrue(text.contains("filename=\"profile.jpg\"")); assertTrue(text.contains("image/jpeg"))
                    assertTrue(body.toList().windowed(jpeg.size).any { it == jpeg.toList() })
                    respond("""{"userId":7,"hasProfilePhoto":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                HttpMethod.Get -> respond(jpeg, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
                else -> respond("", HttpStatusCode.NoContent)
            }
        })
        try {
            val repo = HttpOptionalProfileRepository(client)
            assertTrue(assertIs<ApiResult.Ok<ProfileResponse>>(repo.upload(7, jpeg)).value.hasProfilePhoto)
            assertContentEquals(jpeg, assertIs<ApiResult.Ok<ByteArray>>(repo.photo(7)).value)
            assertIs<ApiResult.Ok<Unit>>(repo.deletePhoto(7))
            assertEquals(listOf("POST" to "/dev/api/users/7/profile/photos", "GET" to "/dev/api/users/7/profile/photo", "DELETE" to "/dev/api/users/7/profile/photo"), paths)
        } finally { client.close() }
    }
}
