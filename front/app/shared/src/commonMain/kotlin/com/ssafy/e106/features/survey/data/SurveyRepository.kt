package com.ssafy.e106.features.survey.data

import com.ssafy.e106.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class SurveyManifest(val surveyVersion: String, val wordingVersion: String, val contentHash: String)
@Serializable data class WireOption(val value: JsonPrimitive, val label: String)
@Serializable data class WireQuestion(
    val questionId: String, val ordinal: Int, val text: String,
    val responseSchema: JsonObject, val options: List<WireOption>,
    val allowUnknown: Boolean, val allowAny: Boolean,
)
@Serializable data class Questionnaire(val manifest: SurveyManifest, val questionCount: Int, val questions: List<WireQuestion>)
@Serializable data class SavedAnswer(val questionId: String, val value: JsonElement)
@Serializable data class SurveySession(
    val sessionId: Long, val manifest: SurveyManifest, val status: String,
    val answeredCount: Int, val questionCount: Int, val nextQuestionId: String? = null,
    val answers: List<SavedAnswer>, val completedAt: String? = null,
)

interface SurveyRepository {
    suspend fun recovery(userId: Long): ApiResult<SurveyAccountRecovery>
    suspend fun migrationPlan(id: Long, target: String): ApiResult<SurveyMigrationPlan>
    suspend fun migrate(id: Long, target: String, policyHash: String): ApiResult<SurveySession>
    suspend fun complete(id: Long): ApiResult<SurveySession>
    suspend fun requestAnalysis(id: Long): ApiResult<AnalysisReceipt>
    suspend fun analysis(id: Long): ApiResult<AnalysisResult>
    suspend fun current(): ApiResult<Questionnaire>
    suspend fun start(version: String): ApiResult<SurveySession>
    suspend fun get(id: Long): ApiResult<SurveySession>
    suspend fun questionnaire(id: Long): ApiResult<Questionnaire>
    suspend fun save(id: Long, questionId: String, value: JsonElement): ApiResult<SurveySession>
    suspend fun delete(id: Long, questionId: String): ApiResult<SurveySession>
}

class HttpSurveyRepository(private val client: HttpClient) : SurveyRepository {
    // 기존 인증 HttpClient를 사용해 토큰 회원의 가입 단계에서 복구용 식별자만 읽는다.
    override suspend fun recovery(userId: Long): ApiResult<SurveyAccountRecovery> =
        when (val result = client.call<JsonObject> { get("users/$userId/onboarding") }) {
            is ApiResult.Fail -> result
            is ApiResult.Ok -> try {
                // 공통 JSON 설정은 누락된 nullable 필드를 null로 읽으므로, 신규 계약의 키 존재를 먼저 검사한다.
                require(result.value.keys.containsAll(listOf("surveySessionId", "latestCompletedSessionId", "latestAnalysis", "activeResultId")))
                ApiResult.Ok(ApiJson.decodeFromJsonElement<SurveyAccountRecovery>(result.value).checked())
            } catch (e: IllegalArgumentException) { ApiResult.Fail(AppError.Unknown(e)) }
        }
    override suspend fun migrationPlan(id: Long, target: String) = client.call<SurveyMigrationPlan> {
        get("survey-sessions/$id/migration-plan") { parameter("targetSurveyVersion", target) }
    }
    override suspend fun migrate(id: Long, target: String, policyHash: String) = client.call<SurveySession> {
        post("survey-sessions/$id/migrations") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("targetSurveyVersion", target); put("policyHash", policyHash) })
        }
    }
    override suspend fun complete(id: Long) = client.call<SurveySession> { post("survey-sessions/$id/complete") }
    override suspend fun requestAnalysis(id: Long) = client.call<AnalysisReceipt> { post("survey-sessions/$id/analyses") }
    override suspend fun analysis(id: Long) = client.call<AnalysisResult> { get("analysis-results/$id") }
    override suspend fun current() = client.call<Questionnaire> { get("surveys/current") }
    override suspend fun start(version: String) = client.call<SurveySession> {
        post("survey-sessions") { contentType(ContentType.Application.Json); setBody(buildJsonObject { put("surveyVersion", version) }) }
    }
    override suspend fun get(id: Long) = client.call<SurveySession> { get("survey-sessions/$id") }
    override suspend fun questionnaire(id: Long) = client.call<Questionnaire> { get("survey-sessions/$id/questionnaire") }
    override suspend fun save(id: Long, questionId: String, value: JsonElement) = client.call<SurveySession> {
        put("survey-sessions/$id/answers/${questionId.encodeURLPathPart()}") {
            contentType(ContentType.Application.Json); setBody(buildJsonObject { put("value", value) })
        }
    }
    override suspend fun delete(id: Long, questionId: String) = client.call<SurveySession> {
        delete("survey-sessions/$id/answers/${questionId.encodeURLPathPart()}")
    }
}
