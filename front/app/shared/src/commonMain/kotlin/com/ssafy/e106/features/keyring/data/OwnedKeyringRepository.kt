package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable

/** 서버 등록 정보만 읽는다. READY를 현재 BLE 연결 성공이나 배터리 상태로 변환하지 않는다. */
@Serializable
data class OwnedKeyring(val deviceId: Long, val status: String, val firmwareVersion: String)

@Serializable
private data class OwnedKeyringsResponse(val items: List<OwnedKeyring>)

interface OwnedKeyringRepository {
    suspend fun list(): ApiResult<List<OwnedKeyring>>
}

/** 인증·토큰 갱신은 기존 HttpClient가 담당하며, 민감한 소유 목록을 별도로 저장하지 않는다. */
class HttpOwnedKeyringRepository(private val client: HttpClient) : OwnedKeyringRepository {
    override suspend fun list(): ApiResult<List<OwnedKeyring>> {
        val result = client.call<OwnedKeyringsResponse> {
            get("keyrings") { header(HttpHeaders.CacheControl, "no-store") }
        }
        return when (result) {
            is ApiResult.Fail -> result
            is ApiResult.Ok -> {
                val items = result.value.items
                // 잘못된 성공 응답을 '등록 없음'으로 바꾸면 재등록을 잘못 유도할 수 있다.
                if (items.any { it.deviceId <= 0 || it.status.isBlank() || it.firmwareVersion.length !in 1..100 } ||
                    items.map { it.deviceId }.distinct().size != items.size) {
                    ApiResult.Fail(AppError.Unknown(IllegalStateException("Invalid keyring response")))
                } else ApiResult.Ok(items)
            }
        }
    }
}
