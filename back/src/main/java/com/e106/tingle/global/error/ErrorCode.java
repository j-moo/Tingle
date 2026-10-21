package com.e106.tingle.global.error;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 앱에 내려가는 오류 코드. 앱은 이름(code)으로 분기하므로 이름을 바꾸면 docs/contracts 명세도 함께 바꾼다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	// 공통
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
	AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	ONBOARDING_REQUIRED(HttpStatus.FORBIDDEN, "가입 단계를 먼저 완료해 주세요."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
	IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "다른 요청에 사용된 재시도 키입니다."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "제안을 찾을 수 없어요."),

	// 매칭 제안
	PROPOSAL_EXPIRED(HttpStatus.GONE, "제안의 응답 시간이 지났거나 접근이 종료됐어요."),
	PROPOSAL_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 상태에서는 선택을 바꿀 수 없어요."),

	// 소셜 로그인
	INVALID_PROVIDER(HttpStatus.BAD_REQUEST, "지원하지 않는 로그인 방식입니다."),
	INVALID_SOCIAL_TOKEN(HttpStatus.UNAUTHORIZED, "소셜 로그인 정보를 확인할 수 없습니다."),
	SOCIAL_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "소셜 로그인 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."),

	// 약관 동의
	REQUIRED_AGREEMENT_MISSING(HttpStatus.BAD_REQUEST, "필수 항목에 모두 동의해 주세요."),
	AGREEMENT_VERSION_OUTDATED(HttpStatus.CONFLICT, "약관이 변경되었습니다. 변경된 약관을 확인해 주세요."),

	// 프로필
	INVALID_NICKNAME(HttpStatus.BAD_REQUEST, "닉네임은 2~20자의 한글, 영문, 숫자, 공백만 사용할 수 있습니다."),
	INVALID_BIRTH_DATE(HttpStatus.BAD_REQUEST, "생년월일을 확인해 주세요."),
	INVALID_INTRODUCTION(HttpStatus.BAD_REQUEST, "자기소개는 200자, 줄바꿈 3개까지 쓸 수 있습니다."),
	UNDER_AGE(HttpStatus.FORBIDDEN, "만 19세 이상만 이용할 수 있습니다."),
	NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
	PROFILE_FIELD_LOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "가입을 마친 뒤에는 성별과 생년월일을 바꿀 수 없습니다."),

	// 액세스 토큰 (앱 처리: EXPIRED 만 재발급 1회 시도, INVALID·SESSION_REVOKED 는 바로 로그인 화면)
	ACCESS_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다."),
	ACCESS_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),
	SESSION_REVOKED(HttpStatus.UNAUTHORIZED, "로그인 세션이 종료되었습니다. 다시 로그인해 주세요."),

	// 토큰 재발급·로그아웃
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요.");

	private final HttpStatus status;
	private final String message;
}
