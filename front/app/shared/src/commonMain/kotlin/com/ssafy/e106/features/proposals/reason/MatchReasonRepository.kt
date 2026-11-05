package com.ssafy.e106.features.proposals.reason

import com.ssafy.e106.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable

@Serializable data class ReasonTask(val proposalId: Long, val kind: String, val status: String,
    val statusUrl: String, val errorCode: String?, val retryable: Boolean, val pollAfterSeconds: Long)
@Serializable data class ReasonResult(val status: String, val source: String?, val text: String?,
    val evidenceKeys: List<String>, val promptVersion: String?)

interface MatchReasonRepository {
    suspend fun request(id: Long, key: String): ApiResult<ReasonTask>
    suspend fun get(id: Long): ApiResult<ReasonResult>
}
class HttpMatchReasonRepository(private val client: HttpClient) : MatchReasonRepository {
    // 근거/프로필을 본문으로 보내지 않는다. 공개 API가 서버에 고정된 당시 근거를 선택한다.
    override suspend fun request(id: Long, key: String) = client.call<ReasonTask> {
        post("match-proposals/$id/explanations") { header("Idempotency-Key", key) }
    }
    // 응답의 statusUrl로 임의 URL을 호출하지 않고 확인된 같은 서버 경로를 사용한다.
    override suspend fun get(id: Long) = client.call<ReasonResult> {
        get("match-proposals/$id/explanation") { header(HttpHeaders.CacheControl, "no-store") }
    }
}
