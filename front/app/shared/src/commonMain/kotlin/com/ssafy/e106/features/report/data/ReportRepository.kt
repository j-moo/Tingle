package com.ssafy.e106.features.report.data

import com.ssafy.e106.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import kotlinx.serialization.Serializable

@Serializable data class ReportBody(val chatRoomId: Long, val reason: String, val messageIds: List<Long>, val detail: String?)
@Serializable data class ReportReceipt(val reportId: Long, val status: String, val createdAt: String)
@Serializable data class PendingReport(val owner: Long, val key: String, val body: ReportBody)
interface ReportRepository { suspend fun submit(request: PendingReport): ApiResult<ReportReceipt> }

/** 접수 API만 호출한다. 신고 성공을 차단/정지 성공으로 바꾸지 않는다. */
class HttpReportRepository(private val client: HttpClient) : ReportRepository {
    override suspend fun submit(request: PendingReport) = client.call<ReportReceipt> {
        post("reports") { header("Idempotency-Key", request.key); setBody(request.body) }
    }
}
