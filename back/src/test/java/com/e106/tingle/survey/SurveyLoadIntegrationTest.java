package com.e106.tingle.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;

/**
 * 설문 저장·조회·완료, 개인 분석 결과 조회, 가입 단계 조회의 요청당 SQL 수와 로컬 지연(S15P31E106-210).
 * SQL 수는 JDBC 실행(Hibernate·JdbcTemplate 모두)을 세어 상한으로 지키고, 지연은 출력만 한다(로컬 수치라 운영 기준이 아님).
 * 부하 조건: 회원 {@value #MEMBERS}명이 동시에 각자 회차로 요청 {@value #ROUNDS}번씩. 회차에는 99문항 답이 이미 있다.
 * 개선 전(권한 검사가 모든 가입 단계를 조회하던 때) 요청당 SQL: 답 저장 10, 회차 조회 8, 분석 결과 8, 답 저장+완료 21,
 * 가입 단계 조회 15(설문 단계)·23(READY).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import({TestcontainersConfiguration.class, SurveyLoadIntegrationTest.QueryCounter.class})
class SurveyLoadIntegrationTest extends IntegrationTestSupport {

	private static final int MEMBERS = 32;
	private static final int ROUNDS = 10;
	private static final String SESSIONS = "/api/survey-sessions/";

	@Test
	void surveyAndAnalysisRequestsStayBoundedUnderConcurrentMembers() throws Exception {
		List<JsonNode> members = new ArrayList<>();
		List<Long> sessions = new ArrayList<>();
		for (int index = 0; index < MEMBERS; index++) {
			JsonNode tokens = login("kakao", newSocialId());
			agree(tokens).andExpect(status().isOk());
			bindKeyring(userIdOf(tokens));
			patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
			completeProfile(tokens).andExpect(status().isOk());
			long sessionId = jsonMapper.readTree(perform(post("/api/survey-sessions"), tokens,
				"{\"surveyVersion\":\"SIS-Q-1.1-100\"}").andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString()).get("sessionId").asLong();
			jdbc.update("insert into survey_answer(survey_id,question_id,answer_value,answer_type,answered_at) "
				+ "select ?, 'Q' || lpad(n::text,3,'0'), 'unknown', 'STRING', now() from generate_series(1,99) n",
				sessionId);
			members.add(tokens);
			sessions.add(sessionId);
		}

		measure("SAVE_ANSWER", 6, i -> perform(put(SESSIONS + sessions.get(i) + "/answers/Q0" + (10 + i % 50)),
			members.get(i), "{\"value\":\"unknown\"}").andExpect(status().isOk()));
		measure("GET_SESSION", 5, i -> perform(get(SESSIONS + sessions.get(i)), members.get(i), null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.answeredCount").value(99)));
		// 가입 단계 조회는 새 회차 기본 조합(rule_channel 한 줄)을 매번 읽어 210 측정 때보다 1건 많다(서버 여러 대가 같은 값을 보게).
		measure("ONBOARDING_SURVEY_REQUIRED", 15, i -> perform(get(me(members.get(i), "/onboarding")),
			members.get(i), null).andExpect(status().isOk()).andExpect(jsonPath("$.answeredCount").value(99)));
		measureOnce("COMPLETE", 13, i -> {
			perform(put(SESSIONS + sessions.get(i) + "/answers/Q100"), members.get(i), "{\"value\":\"unknown\"}")
				.andExpect(status().isOk());
			return perform(post(SESSIONS + sessions.get(i) + "/complete"), members.get(i), null)
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
		});

		List<Long> results = new ArrayList<>();
		for (long sessionId : sessions) {
			results.add(jdbc.queryForObject("insert into personality(survey_id,analysis_version,status,result) "
				+ "values (?,'load-analysis','SUCCEEDED','{\"scores\":{}}') returning id", Long.class, sessionId));
		}
		measure("ANALYSIS_RESULT", 5, i -> perform(get("/api/analysis-results/" + results.get(i)),
			members.get(i), null).andExpect(status().isOk()));
		// 남의 결과는 그대로 404(계정 경계)
		perform(get("/api/analysis-results/" + results.get(1)), members.get(0), null)
			.andExpect(status().isNotFound());

		List<JsonNode> ready = new ArrayList<>();
		for (int index = 0; index < MEMBERS; index++) {
			JsonNode tokens = login("kakao", newSocialId());
			completeOnboarding(tokens);
			ready.add(tokens);
		}
		measure("ONBOARDING_READY", 22, i -> perform(get(me(ready.get(i), "/onboarding")), ready.get(i), null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.nextStep").value("READY")));
	}

	@Test
	void concurrentSavesOnOneSessionNeverLandAfterCompletion() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
		completeProfile(tokens).andExpect(status().isOk());
		long sessionId = jsonMapper.readTree(perform(post("/api/survey-sessions"), tokens,
			"{\"surveyVersion\":\"SIS-Q-1.1-100\"}").andReturn().getResponse().getContentAsString())
			.get("sessionId").asLong();
		jdbc.update("insert into survey_answer(survey_id,question_id,answer_value,answer_type,answered_at) "
			+ "select ?, 'Q' || lpad(n::text,3,'0'), 'unknown', 'STRING', now() from generate_series(1,100) n",
			sessionId);

		List<Integer> codes = runConcurrently(
			() -> perform(post(SESSIONS + sessionId + "/complete"), tokens, null).andReturn().getResponse()
				.getStatus(),
			() -> save(tokens, sessionId, "Q001"), () -> save(tokens, sessionId, "Q002"),
			() -> save(tokens, sessionId, "Q003"), () -> save(tokens, sessionId, "Q004"));

		assertThat(codes.getFirst()).isEqualTo(200);
		assertThat(codes.subList(1, codes.size())).allMatch(code -> code == 200 || code == 409);
		// 완료 뒤에 저장된 답은 없다(회차 행 잠금으로 줄 세움)
		assertThat(jdbc.queryForObject("select count(*) from survey_answer a join survey s on s.id=a.survey_id "
			+ "where s.id=? and a.answered_at > s.completed_at", Long.class, sessionId)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from survey_answer where survey_id=?", Long.class,
			sessionId)).isEqualTo(100);
	}

	private int save(JsonNode tokens, long sessionId, String questionId) throws Exception {
		return perform(put(SESSIONS + sessionId + "/answers/" + questionId), tokens,
			"{\"value\":\"unknown\"}").andReturn().getResponse().getStatus();
	}

	// 회원별로 한 스레드씩 ROUNDS 번 요청한다. 같은 동시 요청을 3번씩 먼저 돌려 데우고 그 결과는 버린다.
	private void measure(String scenario, int maxQueries, Request request) throws Exception {
		run("WARMUP " + scenario, maxQueries, 3, request);
		run(scenario, maxQueries, ROUNDS, request);
	}

	private void measureOnce(String scenario, int maxQueries, Request request) throws Exception {
		run(scenario, maxQueries, 1, request);
	}

	private void run(String scenario, int maxQueries, int rounds, Request request) throws Exception {
		List<Long> nanos = Collections.synchronizedList(new ArrayList<>());
		List<Integer> queries = Collections.synchronizedList(new ArrayList<>());
		ExecutorService executor = Executors.newFixedThreadPool(MEMBERS);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<?>> futures = new ArrayList<>();
			for (int member = 0; member < MEMBERS; member++) {
				int index = member;
				futures.add(executor.submit(() -> {
					start.await();
					for (int round = 0; round < rounds; round++) {
						QueryCounter.COUNT.get()[0] = 0;
						long started = System.nanoTime();
						request.call(index);
						nanos.add(System.nanoTime() - started);
						queries.add(QueryCounter.COUNT.get()[0]);
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : futures) {
				future.get(120, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}
		nanos.sort(Long::compare);
		int max = Collections.max(queries);
		System.out.printf("SURVEY_LOAD %s concurrency=%d requests=%d sqlPerRequest=%d p50=%.1fms p95=%.1fms "
				+ "max=%.1fms%n", scenario, MEMBERS, nanos.size(), max, nanos.get(nanos.size() / 2) / 1e6,
			nanos.get((int)Math.ceil(nanos.size() * 0.95) - 1) / 1e6, nanos.getLast() / 1e6);
		assertThat(max).as(scenario + " 요청당 SQL 수").isLessThanOrEqualTo(maxQueries);
	}

	@FunctionalInterface
	private interface Request {
		ResultActions call(int member) throws Exception;
	}

	/** DataSource 를 감싸 이 스레드에서 실행한 SQL(execute*) 수를 센다. */
	@TestConfiguration(proxyBeanMethods = false)
	static class QueryCounter {

		static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);

		@Bean
		static BeanPostProcessor countingDataSource() {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String beanName) {
					return bean instanceof DataSource source ? wrap(DataSource.class, source) : bean;
				}
			};
		}

		private static <T> T wrap(Class<T> type, Object target) {
			return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
				(proxy, method, args) -> {
					if (target instanceof Statement && method.getName().startsWith("execute")) {
						COUNT.get()[0]++;
					}
					Object result;
					try {
						result = method.invoke(target, args);
					} catch (InvocationTargetException failure) {
						throw failure.getCause();
					}
					Class<?> returned = method.getReturnType();
					boolean jdbcObject = Connection.class.equals(returned)
						|| Statement.class.isAssignableFrom(returned);
					return result != null && jdbcObject && !"unwrap".equals(method.getName())
						? wrap(returned, result) : result;
				}));
		}
	}
}
