package com.e106.tingle.global.error;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Spring MVC 밖(서블릿 컨테이너, 필터)에서 난 오류가 넘어오는 /error 경로도 JSON으로 응답한다.
 * 이 빈이 있으면 Spring Boot 기본 오류 컨트롤러(HTML 오류 화면)는 등록되지 않는다.
 */
@RestController
public class JsonErrorController implements ErrorController {

	@RequestMapping("${spring.web.error.path:/error}")
	public ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
		HttpStatus status = statusOf(request);
		ErrorCode errorCode = status == HttpStatus.NOT_FOUND ? ErrorCode.NOT_FOUND
			: status.is4xxClientError() ? ErrorCode.INVALID_REQUEST : ErrorCode.INTERNAL_ERROR;
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_JSON)
			.body(ErrorResponse.from(errorCode));
	}

	private static HttpStatus statusOf(HttpServletRequest request) {
		Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		HttpStatus status = code instanceof Integer value ? HttpStatus.resolve(value) : null;
		return status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
	}
}
