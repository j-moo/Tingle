package com.ssafy.e106.core.network

/** Api · Repository 반환형. 예외 대신 값으로 성공·실패를 돌려준다. 실패 종류는 [AppError] 로 고정 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Fail(val error: AppError) : ApiResult<Nothing>
}

inline fun <T> ApiResult<T>.getOrElse(onFail: (AppError) -> T): T = when (this) {
    is ApiResult.Ok -> value
    is ApiResult.Fail -> onFail(error)
}

inline fun <T, R> ApiResult<T>.map(f: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(f(value))
    is ApiResult.Fail -> this
}
