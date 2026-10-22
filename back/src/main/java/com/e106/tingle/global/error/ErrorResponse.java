package com.e106.tingle.global.error;

/**
 * 모든 오류 응답의 본문. 형식은 docs/contracts/api/auth.md 공통 규칙을 따른다.
 */
public record ErrorResponse(String code, String message) {

	public static ErrorResponse from(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.name(), errorCode.getMessage());
	}
}
