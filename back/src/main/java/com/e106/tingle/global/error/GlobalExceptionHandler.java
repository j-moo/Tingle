package com.e106.tingle.global.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 모든 예외를 ErrorResponse 형식으로 바꾼다.
 * 스프링 MVC 기본 예외(검증 실패, 잘못된 JSON, 없는 경로, 허용되지 않은 메서드 등)는
 * 부모 클래스가 알맞은 HTTP 상태를 정하고, 여기서는 본문만 우리 형식으로 바꾼다.
 * 오류 응답마다 ErrorLog 로 요청 번호·경로·회원 번호·오류 코드를 한 줄 남긴다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(BusinessException exception, HttpServletRequest request) {
		ErrorCode errorCode = exception.getErrorCode();
		// 5xx 업무 오류도 스택을 남긴다(ErrorLog 는 4xx 엔 예외를 붙이지 않는다). 업무 예외 메시지는 오류 코드라 입력값이 없다.
		ErrorLog.write(request, errorCode.getStatus(), errorCode, exception);
		return json(errorCode.getStatus(), errorCode);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
		ErrorLog.write(request, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, exception);
		return json(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
	}

	// 업로드 한도(spring.servlet.multipart) 초과는 스프링 기본 413 대신 사진 규칙 위반(400 PHOTO_INVALID)으로 응답한다.
	@Override
	protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException exception,
		HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ErrorLog.write(servletRequest(request), HttpStatus.BAD_REQUEST, ErrorCode.PHOTO_INVALID, null);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
			.body(ErrorResponse.from(ErrorCode.PHOTO_INVALID));
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
		HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
		ErrorCode errorCode = toErrorCode(statusCode);
		ErrorLog.write(servletRequest(request), statusCode, errorCode, exception);
		return ResponseEntity.status(statusCode).headers(headers)
			.contentType(MediaType.APPLICATION_JSON)
			.body(ErrorResponse.from(errorCode));
	}

	private static HttpServletRequest servletRequest(WebRequest request) {
		return ((ServletWebRequest) request).getRequest();
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
