package com.e106.tingle.global.ratelimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.global.error.ErrorLog;
import com.e106.tingle.global.error.ErrorResponse;
import com.e106.tingle.global.i18n.Messages;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * 같은 사용자가 로그인·토큰 재발급·닉네임 확인을 짧은 시간에 너무 많이 부르지 못하게 막는다.
 * 1분 단위로 횟수를 세고, 넘으면 429 RATE_LIMITED 를 돌려준다(다음 분이 되면 다시 0부터).
 * 로그인·재발급은 로그인 전이라 회원을 모르므로 IP 기준, 닉네임 확인은 로그인 후 API 라 회원 기준이다.
 * 보안 필터 뒤에서 돈다(@Order 없음 = 가장 나중): 닉네임 확인은 인증된 요청만 여기까지 온다.
 * 기준값은 정상 사용(앱 재시도, 여러 화면이 동시에 401 을 받아 재발급, 한 글자씩 닉네임 확인)을 넉넉히 넘게 잡았다.
 */
// 기본 켜짐. 통합 테스트(한 IP 에서 수십 번 로그인)만 app.rate-limit.enabled=false 로 끈다.
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class RateLimitFilter extends OncePerRequestFilter {

	/** IP 하나당 1분에 소셜 로그인 횟수. 통신사 공유 IP(여러 사람이 한 IP)도 고려해 넉넉히 잡았다. */
	static final int LOGIN_PER_MINUTE = 30;
	/** IP 하나당 1분에 토큰 재발급 횟수. 앱 시작 때 여러 화면이 동시에 재발급해도 걸리지 않게. */
	static final int REFRESH_PER_MINUTE = 30;
	/** 회원 한 명당 1분에 닉네임 확인 횟수. 글자를 칠 때마다 확인해도 걸리지 않게. */
	static final int NICKNAME_PER_MINUTE = 60;

	private static final long WINDOW_MILLIS = 60_000;
	// 로그아웃(/api/auth/logout)은 로그인으로 세지 않는다
	private static final Pattern SOCIAL_LOGIN = Pattern.compile("^/api/auth/(?!logout$)[^/]+$");

	private final Clock clock;
	private final JsonMapper jsonMapper;
	// ponytail: 서버 메모리에서 센다. 블루-그린은 한 번에 한 대만 받으므로 충분하고, 여러 대를 동시에 띄우면 Redis 등으로 옮긴다
	private final Map<String, Integer> counts = new HashMap<>();
	private long window;

	public RateLimitFilter(Clock clock, JsonMapper jsonMapper) {
		this.clock = clock;
		this.jsonMapper = jsonMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		// 디코딩한 경로로 비교한다. 원문(getRequestURI)이면 /api/users/nickname-%61vailability 처럼 인코딩해 피해 간다
		// (Spring MVC 는 디코딩해 같은 API 로 연결한다).
		String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
		String method = request.getMethod();
		String key = null;
		int limit = 0;
		if (HttpMethod.POST.matches(method) && path.equals("/api/auth/token/refresh")) {
			key = "refresh:" + clientIp(request);
			limit = REFRESH_PER_MINUTE;
		} else if (HttpMethod.POST.matches(method) && SOCIAL_LOGIN.matcher(path).matches()) {
			key = "login:" + clientIp(request);
			limit = LOGIN_PER_MINUTE;
		} else if ((HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method))
			&& path.equals("/api/users/nickname-availability")) {
			// HEAD 도 같은 GET 처리로 연결되어 닉네임 조회가 실행되므로 같이 센다
			Authentication auth = SecurityContextHolder.getContext().getAuthentication();
			if (auth instanceof JwtAuthenticationToken jwt) {
				key = "nickname:" + jwt.getName();
				limit = NICKNAME_PER_MINUTE;
			}
		}
		if (key != null && overLimit(key, limit)) {
			long now = clock.instant().toEpochMilli();
			long retryAfterSeconds = (WINDOW_MILLIS - now % WINDOW_MILLIS + 999) / 1000;
			// 다른 오류 응답과 같이 요청 번호로 찾을 수 있게 한 줄 남긴다(ErrorLog, 4xx 라 info)
			ErrorLog.write(request, ErrorCode.RATE_LIMITED.getStatus(), ErrorCode.RATE_LIMITED, null);
			response.setStatus(ErrorCode.RATE_LIMITED.getStatus().value());
			response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			// RATE_LIMITED 기본 문구는 아이스브레이커(요청 키 재사용) 안내라 여기서는 "잠시 후 다시" 문구를 쓴다
			jsonMapper.writeValue(response.getOutputStream(),
				new ErrorResponse(ErrorCode.RATE_LIMITED.name(), Messages.get("error.RATE_LIMITED.retryLater")));
			return;
		}
		chain.doFilter(request, response);
	}

	private synchronized boolean overLimit(String key, int limit) {
		long current = clock.instant().toEpochMilli() / WINDOW_MILLIS;
		if (current != window) {
			counts.clear();
			window = current;
		}
		return counts.merge(key, 1, Integer::sum) > limit;
	}

	/**
	 * nginx 가 X-Real-IP 를 접속한 IP($remote_addr)로 덮어써 넘긴다. back 은 호스트에 포트를 열지 않아 nginx 를 거친 요청만 온다.
	 * X-Forwarded-For 는 앱이 보낸 값 뒤에 이어 붙는 방식이라 맨 앞 값을 믿을 수 없어 쓰지 않는다. 로컬(nginx 없음)은 접속 IP.
	 */
	private static String clientIp(HttpServletRequest request) {
		String realIp = request.getHeader("X-Real-IP");
		return realIp == null || realIp.isBlank() ? request.getRemoteAddr() : realIp;
	}
}
