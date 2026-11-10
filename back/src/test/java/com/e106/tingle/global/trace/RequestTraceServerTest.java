package com.e106.tingle.global.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.filter.OncePerRequestFilter;

import com.e106.tingle.TestcontainersConfiguration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 컨트롤러 전(필터)에서 난 예외는 MockMvc 로는 재현되지 않는다(컨테이너의 /error 재요청이 없다). 실제 서버로 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "erd"})
@Import({TestcontainersConfiguration.class, RequestTraceServerTest.FailingFilter.class})
@ExtendWith(OutputCaptureExtension.class)
class RequestTraceServerTest {

	private static final String FAILING_PATH = "/api/v1/test/filter-failure";

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("필터에서 난 500 응답에도 요청 번호가 붙고, 같은 번호로 오류 로그를 찾을 수 있다")
	void filterFailureKeepsRequestIdInHeaderAndLog(CapturedOutput output) throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + FAILING_PATH))
				.header("X-Request-Id", "not-a-uuid").build(),
			HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(500);
		assertThat(response.body()).contains("\"code\":\"INTERNAL_ERROR\"");
		String issued = response.headers().firstValue("X-Request-Id").orElseThrow();
		assertThat(UUID.fromString(issued).toString()).isEqualTo(issued);
		// 처음 요청 때 만든 번호가 /error 재요청에서도 그대로 쓰여, 헤더와 로그가 같은 번호를 가진다.
		assertThat(output.getAll().lines()).anyMatch(line -> line.contains("[" + issued + "] ")
			&& line.contains("event=request_failed status=500 code=INTERNAL_ERROR method=GET path=" + FAILING_PATH));
		// 첫 요청에서 본 번호도 같다(재요청에서 새 번호를 만들면 이 줄이 없다)
		assertThat(output.getAll().lines()).anyMatch(line -> line.contains("[" + issued + "] ")
			&& line.contains("first-dispatch-seen"));
	}

	@TestConfiguration
	static class FailingFilter {

		// 보안 필터보다 먼저(요청 번호 필터 바로 다음) 돌게 해, 인증 전에 난 예외를 흉내 낸다.
		@Bean
		FilterRegistrationBean<OncePerRequestFilter> failingFilter() {
			OncePerRequestFilter failing = new OncePerRequestFilter() {
				@Override
				protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
					FilterChain chain) throws ServletException, IOException {
					if (FAILING_PATH.equals(request.getRequestURI())) {
						// 처음 요청 때 붙은 번호를 로그로 남겨, /error 재요청이 같은 번호를 다시 쓰는지 테스트가 확인한다
						org.slf4j.LoggerFactory.getLogger("failing-filter").info("first-dispatch-seen");
						throw new IllegalStateException("synthetic filter failure");
					}
					chain.doFilter(request, response);
				}
			};
			FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(failing);
			registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
			return registration;
		}
	}
}
