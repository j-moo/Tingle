package com.e106.tingle.global.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestProtectedController;
import com.e106.tingle.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import({TestcontainersConfiguration.class, TestProtectedController.class})
class RequestTraceIntegrationTest extends IntegrationTestSupport {

	@Autowired
	private ThreadPoolTaskScheduler scheduler;

	@Test
	@DisplayName("앱이 보낸 UUID 추적 번호는 보안 필터가 막은 401 응답에도 그대로 돌려준다")
	void echoesClientRequestIdEvenOnUnauthorized() throws Exception {
		String requestId = UUID.randomUUID().toString();
		mockMvc.perform(get("/api/v1/test/protected").header("X-Request-Id", requestId))
			.andExpect(status().isUnauthorized()).andExpect(header().string("X-Request-Id", requestId));
	}

	@Test
	@DisplayName("UUID가 아니거나 여러 개인 추적 번호는 버리고 새 UUID를 만든다(로그에 임의 문자열을 넣지 않는다)")
	void replacesInvalidRequestId() throws Exception {
		String forged = "synthetic\nforged-log-line";
		String issued = mockMvc.perform(get("/api/v1/test/protected").header("X-Request-Id", forged))
			.andReturn().getResponse().getHeader("X-Request-Id");
		assertThat(UUID.fromString(issued).toString()).isEqualTo(issued);

		String first = UUID.randomUUID().toString();
		String twice = mockMvc.perform(get("/api/v1/test/protected").header("X-Request-Id", first)
				.header("X-Request-Id", UUID.randomUUID().toString()))
			.andReturn().getResponse().getHeader("X-Request-Id");
		assertThat(twice).isNotEqualTo(first);
		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
	}

	@Test
	@DisplayName("예약 작업은 실행마다 새 추적 번호를 MDC에 두고, 실행이 끝나면 지운다")
	void scheduledRunsGetTheirOwnRequestId() throws Exception {
		List<String> seen = new CopyOnWriteArrayList<>();
		List<String> after = new CopyOnWriteArrayList<>();
		CountDownLatch runs = new CountDownLatch(2);
		var future = scheduler.scheduleWithFixedDelay(() -> {
			seen.add(String.valueOf(MDC.get(RequestIdFilter.MDC_KEY)));
			runs.countDown();
		}, Duration.ofMillis(10));
		try {
			assertThat(runs.await(5, TimeUnit.SECONDS)).isTrue();
		} finally {
			future.cancel(false);
		}
		scheduler.submit(() -> after.add(String.valueOf(MDC.get(RequestIdFilter.MDC_KEY)))).get();
		assertThat(seen.subList(0, 2)).allSatisfy(id -> assertThat(UUID.fromString(id).toString()).isEqualTo(id))
			.doesNotHaveDuplicates();
		// 일회성 실행도 같은 장식을 거친다. 이전 실행의 번호가 스레드에 남지 않는다.
		assertThat(after.get(0)).isNotIn(seen);
	}

	@Test
	@DisplayName("DB 제약 위반 예외 메시지에 입력값(이메일 등)이 들어가지 않는다")
	void databaseErrorsDoNotEchoInputValues() {
		String email = "synthetic-" + UUID.randomUUID() + "@example.test";
		jdbc.execute((ConnectionCallback<Void>) connection -> {
			try (Statement statement = connection.createStatement()) {
				statement.execute("create temp table log_mask_probe(email text primary key)");
				try {
					statement.execute("insert into log_mask_probe values ('" + email + "')");
					assertThatThrownBy(() -> statement.execute("insert into log_mask_probe values ('" + email + "')"))
						.isInstanceOf(SQLException.class)
						.hasMessageContaining("log_mask_probe_pkey").hasMessageNotContaining(email);
				} finally {
					statement.execute("drop table log_mask_probe");
				}
			}
			return null;
		});
	}
}
