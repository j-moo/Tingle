package com.ssafy.e106.features.profile

import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.profile.data.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ProfileContractTest {
    @Test fun partialProfilePatchOmitsUntouchedOptionalValuesAndDecodesNulls() = runTest {
        var path = ""; var body = ""
        val client = createHttpClient("https://test/dev/api/", httpLog = false, engine = MockEngine { request ->
            path = request.url.encodedPath; body = request.body.toByteArray().decodeToString()
            respond("""{"userId":7,"nickname":"Test","birthDate":null,"gender":null,"smokingStatus":null,"drinkingFrequency":null,"religion":null,"introduction":null,"hasProfilePhoto":false,"completed":false} """,
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val result = HttpProfileRepository(client).save(7, buildJsonObject { put("nickname", "Test") })
            assertEquals("/dev/api/users/7/profile", path)
            assertEquals(setOf("nickname"), Json.parseToJsonElement(body).jsonObject.keys)
            assertNull(assertIs<ApiResult.Ok<ProfileResponse>>(result).value.religion)
        } finally { client.close() }
    }
}
