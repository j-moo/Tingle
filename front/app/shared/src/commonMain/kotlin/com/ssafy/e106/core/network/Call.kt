package com.ssafy.e106.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.Serializable

/**
 * 응답을 [ApiResult] 로 바꾸는 유일한 자리. Api 함수는 전부 이걸 감싼다.
 * ```
 * suspend fun fetch(userId: Long): ApiResult<ProfileResponse> = client.call { get("users/$userId/profile") }
 * ```
 * 예외를 밖으로 던지지 않는다. 단 코루틴 취소(화면을 떠남)는 오류가 아니므로 그대로 올린다.
 */
suspend inline fun <reified T> HttpClient.call(crossinline request: suspend HttpClient.() -> HttpResponse): ApiResult<T> =
    try {
        val res = request()
        if (res.status.isSuccess()) ApiResult.Ok(if (T::class == Unit::class) Unit as T else res.body<T>())
        else ApiResult.Fail(errorOf(res))
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        ApiResult.Fail(AppError.Offline)        // 연결 실패 · 타임아웃(HttpRequestTimeoutException 도 IOException). 재발급의 연결 실패도
    } catch (e: RefreshFailed) {
        ApiResult.Fail(e.error)                 // 재발급 5xx. 토큰은 남아 있다
    } catch (e: Exception) {
        ApiResult.Fail(AppError.Unknown(e))     // 성공 응답 파싱 실패 등
    }

/** runCatching 과 같지만 코루틴 취소는 잡지 않고 다시 던진다(화면을 떠난 것은 오류가 아니다) */
internal suspend inline fun <T> catchingNonCancel(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** 서버 오류 본문은 {code, message} 두 필드뿐(B/global/error/ErrorResponse.java). 본문이 깨져 있으면 빈 값 → 기본 문구 */
@Serializable
@PublishedApi
internal class ErrorBody(val code: String = "", val message: String = "")

@PublishedApi
internal suspend fun errorOf(res: HttpResponse): AppError {
    val body = try {
        res.body<ErrorBody>()
    } catch (e: CancellationException) {
        throw e                                  // 본문을 읽다 화면을 떠난 것도 오류가 아니다
    } catch (e: Exception) {
        ErrorBody()                              // 본문이 깨졌으면 빈 값 → 기본 문구
    }
    return when {
        res.status.value == 401 && body.code in SESSION_CODES -> AppError.SessionExpired
        res.status.value == 403 && body.code == "ONBOARDING_REQUIRED" -> AppError.OnboardingRequired(body.message)
        else -> AppError.Server(res.status.value, body.code, body.message)
    }
}

/** 세션이 끝난 401 만. 로그인의 INVALID_SOCIAL_TOKEN, 로그아웃의 INVALID_REFRESH_TOKEN 은 Server 로 남는다 */
@PublishedApi
internal val SESSION_CODES = setOf("AUTH_REQUIRED", "ACCESS_TOKEN_INVALID", "ACCESS_TOKEN_EXPIRED", "SESSION_REVOKED")
