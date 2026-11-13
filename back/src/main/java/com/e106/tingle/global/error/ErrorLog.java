package com.e106.tingle.global.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 오류 응답 한 건을 로그 한 줄로 남긴다. 요청 번호(X-Request-Id)는 로그 패턴이 MDC에서 붙인다(global/trace).
 * 남기는 것은 메서드·경로(쿼리 제외)·회원 번호(JWT sub, 알 때만)·상태·오류 코드뿐이다.
 * 토큰·이메일·소셜 ID·요청 본문은 남기지 않는다. 예외는 5xx일 때만 붙인다(4xx 예외 메시지엔 입력값이 섞일 수 있다).
 * 5xx 는 원인 파악을 위해 스택을 남기며, 이때 예외 메시지(원인 예외 포함)도 함께 찍힌다(이 클래스 이전의 500 로그와 같음).
 * 그래서 예외 메시지에 토큰·개인정보를 넣지 않는다.
 */
@Slf4j
public final class ErrorLog {

	private static final String FORMAT = "event=request_failed status={} code={} method={} path={} memberId={}";

	private ErrorLog() {
	}

	public static void write(HttpServletRequest request, HttpStatusCode status, ErrorCode code, Throwable cause) {
		write(request, status, code.name(), cause);
	}

	/** ErrorCode 에 없는 별도 형식 응답(예: 409 REJOIN_RESTRICTED)용. */
	public static void write(HttpServletRequest request, HttpStatusCode status, String code, Throwable cause) {
		Object[] args = {status.value(), code, request.getMethod(), pathOf(request), memberId()};
		if (status.is5xxServerError()) {
			// 마지막 인자가 예외면 SLF4J 가 스택을 붙이고, null 이면 무시한다.
			log.error(FORMAT, args[0], args[1], args[2], args[3], args[4], cause);
		} else if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
			log.warn(FORMAT, args);
		} else {
			log.info(FORMAT, args);
		}
	}

	// /error 로 넘어온 요청은 원래 경로를 남긴다. getRequestURI 는 디코딩 전 값이라 줄바꿈 같은 문자가 그대로 들어가지 않는다.
	private static String pathOf(HttpServletRequest request) {
		Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
		return original instanceof String path ? path : request.getRequestURI();
	}

	private static String memberId() {
		return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
			? token.getName() : "-";
	}
}
