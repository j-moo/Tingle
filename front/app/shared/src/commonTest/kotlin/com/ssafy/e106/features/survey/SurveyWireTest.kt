package com.ssafy.e106.features.survey

import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.survey.data.*
import com.ssafy.e106.features.survey.ui.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SurveyWireTest {
    @Test fun legacyAnswersBecomeUnansweredWithoutWeakeningResponseValidation() {
        // 조회의 answeredCount/nextQuestionId는 저장 행 기준이므로 먼저 그 계약을 검증한다.
        val raw = SurveySession(11, surveyManifest, "DRAFT", 2, 2, null, listOf(
            SavedAnswer("Q001", JsonPrimitive("4")), SavedAnswer("Q002", JsonPrimitive("any"))))
        val checked = raw.checked(surveyDefinition)
        assertEquals(1, checked.answeredCount); assertEquals("Q001", checked.nextQuestionId)
        assertEquals(listOf("Q002"), checked.answers.map { it.questionId })
        assertEquals(2, raw.answers.size)
        assertFails { raw.copy(answeredCount = 1).checked(surveyDefinition) }
        assertFails { raw.copy(nextQuestionId = "Q001").checked(surveyDefinition) }
        assertFails { raw.copy(answers = raw.answers + raw.answers.first()).checked(surveyDefinition) }
        assertFails { raw.copy(answers = raw.answers.map { it.copy(questionId = "missing") }).checked(surveyDefinition) }
        assertFails { raw.copy(manifest = surveyManifest.copy(surveyVersion = "other")).checked(surveyDefinition) }
        // 완료 회차는 수정 API가 금지된다. 불러오기는 허용해 기존 분석/재검사 경로를 유지한다.
        assertEquals("COMPLETED", raw.copy(status = "COMPLETED").checked(surveyDefinition).status)
    }
    @Test fun booleanAndNullOptionsRemainUnsupportedByPublicAnswerContract() {
        for (value in listOf(JsonPrimitive(true), JsonPrimitive(false), JsonNull)) {
            val question = numericQuestion.copy(responseSchema = buildJsonObject { put("const", value) },
                options = listOf(WireOption(value, "unsupported")), allowUnknown = false)
            assertNull(question.wire(SurveyAnswer.Choices(setOf(value.toString()))))
            assertFails { Questionnaire(surveyManifest, 1, listOf(question)).checked() }
        }
    }
    @Test fun schemaDoesNotCoerceNumericStringsOrAllowMixedSpecialValues() {
        assertTrue(matches(numericQuestion.responseSchema, JsonPrimitive(4)))
        assertFalse(matches(numericQuestion.responseSchema, JsonPrimitive("4")))
        assertFalse(matches(multiQuestion.responseSchema, JsonArray(listOf(JsonPrimitive("walk"), JsonPrimitive("any")))))
        assertFalse(matches(multiQuestion.responseSchema, JsonArray(listOf(JsonPrimitive("walk"), JsonPrimitive("walk")))))
        assertFalse(matches(multiQuestion.responseSchema, JsonArray(listOf(JsonPrimitive("walk"), JsonPrimitive("talk"), JsonPrimitive("rest")))))
        assertFails { numericQuestion.copy(responseSchema = buildJsonObject { put("minimum", 1) }).screen(2, null, SurveySaveState.Unanswered) }
    }
    @Test fun repositoryUsesPublicPathsAndValueOnlyWithoutInventedRevisionHeaders() = runTest {
        val requests = mutableListOf<Triple<String,String,String>>()
        val response = SurveySession(11, surveyManifest, "DRAFT", 0, 2, "Q001", emptyList())
        val client = createHttpClient("https://test/dev/api/", httpLog = false, engine = MockEngine { request ->
            requests += Triple(request.method.value, request.url.encodedPath, request.body.toByteArray().decodeToString())
            assertNull(request.headers["If-Match"]); assertNull(request.headers["Idempotency-Key"])
            respond(ApiJson.encodeToString(SurveySession.serializer(), response), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val repo = HttpSurveyRepository(client)
            assertIs<ApiResult.Ok<*>>(repo.start("v1"))
            repo.save(11, "Q001", JsonPrimitive(4)); repo.save(11, "Q002", JsonArray(listOf(JsonPrimitive("walk"))))
            repo.delete(11, "Q002"); repo.get(11)
            assertEquals("/dev/api/survey-sessions", requests[0].second)
            assertEquals(Json.parseToJsonElement("""{"surveyVersion":"v1"}"""), Json.parseToJsonElement(requests[0].third))
            assertEquals("/dev/api/survey-sessions/11/answers/Q001", requests[1].second)
            assertEquals(JsonPrimitive(4), Json.parseToJsonElement(requests[1].third).jsonObject["value"])
            assertEquals(setOf("value"), Json.parseToJsonElement(requests[2].third).jsonObject.keys)
            assertEquals("DELETE", requests[3].first); assertEquals("GET", requests[4].first)
        } finally { client.close() }
    }
}
