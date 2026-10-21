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

	IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "다른 요청에 사용된 재시도 키입니다."),
	ONBOARDING_REQUIRED(HttpStatus.FORBIDDEN, "가입 단계를 완료해 주세요."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "제안을 찾을 수 없어요."),
	PROPOSAL_EXPIRED(HttpStatus.GONE, "제안의 응답 시간이 지났거나 접근이 종료됐어요."),
	PROPOSAL_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 상태에서는 선택을 바꿀 수 없어요."),
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");

	private final HttpStatus status;
	private final String message;
}
