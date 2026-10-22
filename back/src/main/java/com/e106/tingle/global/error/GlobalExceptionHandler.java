package com.e106.tingle.global.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * 모든 예외를 ErrorResponse 형식으로 바꾼다.
 * 스프링 MVC 기본 예외(검증 실패, 잘못된 JSON, 없는 경로, 허용되지 않은 메서드 등)는
 * 부모 클래스가 알맞은 HTTP 상태를 정하고, 여기서는 본문만 우리 형식으로 바꾼다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(BusinessException exception) {
		ErrorCode errorCode = exception.getErrorCode();
		return json(errorCode.getStatus(), errorCode);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
		log.error("처리하지 못한 예외", exception);
		return json(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
		HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
		ErrorCode errorCode = toErrorCode(statusCode);
		return ResponseEntity.status(statusCode).headers(headers)
			.contentType(MediaType.APPLICATION_JSON)
			.body(ErrorResponse.from(errorCode));
	}

	// 앱 전용 API라 요청의 Accept(예: 브라우저의 text/html)와 상관없이 항상 JSON으로 응답한다.
	private static ResponseEntity<ErrorResponse> json(HttpStatusCode status, ErrorCode errorCode) {
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_JSON)
			.body(ErrorResponse.from(errorCode));
	}

	// 상태 코드는 스프링이 정한 값(400, 404, 405, 415 등)을 그대로 쓰고, 본문의 code만 단순화한다.
	private ErrorCode toErrorCode(HttpStatusCode statusCode) {
		if (statusCode.value() == HttpStatus.NOT_FOUND.value()) {
			return ErrorCode.NOT_FOUND;
		}
		if (statusCode.is4xxClientError()) {
			return ErrorCode.INVALID_REQUEST;
		}
		return ErrorCode.INTERNAL_ERROR;
	}
}
