package com.e106.tingle.global.error;

import lombok.Getter;

/**
 * 서비스 로직에서 정해진 오류 코드로 실패를 알릴 때 던진다. 응답 변환은 GlobalExceptionHandler가 맡는다.
 */
@Getter
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.name());
		this.errorCode = errorCode;
	}
}
