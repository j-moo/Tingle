package com.e106.tingle.global.error;

import org.springframework.http.HttpStatus;

import com.e106.tingle.global.i18n.Messages;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 앱에 내려가는 오류 코드. 앱은 이름(code)으로 분기하므로 이름을 바꾸면 팀 공유 API 계약도 함께 바꾼다.
 * 코드를 추가하면 resources/messages.properties 에 error.{코드} 문구도 추가한다(ErrorMessagesTest 가 확인).
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	// 공통
	INVALID_REQUEST(HttpStatus.BAD_REQUEST),
	AUTH_REQUIRED(HttpStatus.UNAUTHORIZED),
	ONBOARDING_REQUIRED(HttpStatus.FORBIDDEN),
	FORBIDDEN(HttpStatus.FORBIDDEN),
	NOT_FOUND(HttpStatus.NOT_FOUND),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
	SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
	IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
	INVALID_AGE_RANGE(HttpStatus.BAD_REQUEST),
	PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED),
	PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED),

	// 매칭 제안
	PROPOSAL_EXPIRED(HttpStatus.GONE),
	PROPOSAL_STATE_CONFLICT(HttpStatus.CONFLICT),
	PROFILE_CHANGED(HttpStatus.CONFLICT),
	CHAT_CLOSED(HttpStatus.GONE),
	AI_RISK_CONSENT_REQUIRED(HttpStatus.FORBIDDEN),
	INVALID_MESSAGE(HttpStatus.BAD_REQUEST),
	CHAT_NOTICE_REQUIRED(HttpStatus.FORBIDDEN),
	NOTICE_STATE_CONFLICT(HttpStatus.CONFLICT),
	PUSH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

	// 소셜 로그인
	INVALID_PROVIDER(HttpStatus.BAD_REQUEST),
	INVALID_SOCIAL_TOKEN(HttpStatus.UNAUTHORIZED),
	SOCIAL_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY),

	// 약관 동의
	REQUIRED_AGREEMENT_MISSING(HttpStatus.BAD_REQUEST),
	AGREEMENT_VERSION_OUTDATED(HttpStatus.CONFLICT),

	// 프로필
	INVALID_NICKNAME(HttpStatus.BAD_REQUEST),
	INVALID_BIRTH_DATE(HttpStatus.BAD_REQUEST),
	INVALID_INTRODUCTION(HttpStatus.BAD_REQUEST),
	UNDER_AGE(HttpStatus.FORBIDDEN),
	NICKNAME_DUPLICATED(HttpStatus.CONFLICT),
	PROFILE_FIELD_LOCKED(HttpStatus.UNPROCESSABLE_CONTENT),
	PROFILE_INCOMPLETE(HttpStatus.BAD_REQUEST),

	// 액세스 토큰 (앱 처리: EXPIRED 만 재발급 1회 시도, INVALID·SESSION_REVOKED 는 바로 로그인 화면)
	ACCESS_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED),
	ACCESS_TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
	SESSION_REVOKED(HttpStatus.UNAUTHORIZED),

	// 토큰 재발급·로그아웃
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED);

	private final HttpStatus status;

	/**
	 * 현재 요청 언어(Accept-Language)의 사용자 표시 문구.
	 */
	public String getMessage() {
		return Messages.get("error." + name());
	}
}
