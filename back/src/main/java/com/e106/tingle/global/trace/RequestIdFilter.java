package com.e106.tingle.global.trace;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 모든 요청에 추적 번호(X-Request-Id)를 붙여 응답 헤더와 그 요청의 로그에 같은 값을 남긴다.
 * 앱이 UUID 하나를 보내면 그대로 쓰고, 없거나 형식이 다르면 새로 만든다(로그에 임의 문자열이 들어가지 않게).
 * 보안 필터보다 먼저 돌아 401·403 응답에도 붙는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	/** 로그 패턴(logging.pattern.correlation)이 읽는 MDC 키. 예약 작업도 실행마다 같은 키를 쓴다. */
	public static final String MDC_KEY = "requestId";
	private static final Pattern UUID_FORMAT =
		Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String requestId = requestId(request);
		response.setHeader(HEADER, requestId);
		MDC.put(MDC_KEY, requestId);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove(MDC_KEY);
		}
	}

	private static String requestId(HttpServletRequest request) {
		List<String> values = Collections.list(request.getHeaders(HEADER));
		if (values.size() == 1 && UUID_FORMAT.matcher(values.get(0)).matches()) {
			return values.get(0);
		}
		return UUID.randomUUID().toString();
	}
}
