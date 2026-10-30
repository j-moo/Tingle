package com.e106.tingle.global.ai;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

/** 내부 전송 실패 분류. 공급자 응답 본문은 보존하거나 공개하지 않는다. */
public final class AiCallException extends BusinessException {

	private final boolean retryable;
	private final Integer httpStatus;

	public AiCallException(boolean retryable) {
		this(retryable, null);
	}

	public AiCallException(boolean retryable, Integer httpStatus) {
		super(ErrorCode.SERVICE_UNAVAILABLE);
		this.retryable = retryable;
		this.httpStatus = httpStatus;
	}

	public boolean retryable() {
		return retryable;
	}

	public Integer httpStatus() {
		return httpStatus;
	}
}
