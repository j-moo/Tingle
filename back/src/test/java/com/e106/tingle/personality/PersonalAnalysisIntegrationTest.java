package com.e106.tingle.personality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willCallRealMethod;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestProtectedController;
import com.e106.tingle.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 개인 성향 분석 요청·실행·재시도·결과 조회. AI 서버는 가짜(personalAnalysisClient)로 바꾸고 주기 작업은 직접 부른다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import({TestcontainersConfiguration.class, TestProtectedController.class})
class PersonalAnalysisIntegrationTest extends IntegrationTestSupport {

	// 성현님이 실제 계산 코어로 만든 샘플 응답(2026-10-30) 그대로
	private static final String SUCCEEDED = sample("succeeded");
	private static final String INSUFFICIENT = sample("insufficient");

	private static String sample(String name) {
		try {
			return new ClassPathResource("personality/" + name + ".json").getContentAsString(StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Autowired
	private PersonalAnalysisWorker worker;

	// 다른 테스트가 남긴 실행 대상이 이번 테스트의 가짜 AI 호출에 섞이지 않게 한다.
	@BeforeEach
	void parkLeftovers() {
		jdbc.update("update personality set status='FAILED', result='{}' where status in ('PENDING','FAILED')");
	}

	@Test
	@DisplayName("본인 완료 회차만 202로 접수하고, 같은 회차는 같은 결과 번호를 돌려준다")
	void requestRules() throws Exception {
		JsonNode owner = member();
		long ownerId = userIdOf(owner);
		long draft = survey(ownerId, "SIS-Q-1.1-100", null);
		analyze(owner, draft).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SESSION_NOT_COMPLETED"));
		long unsupported = survey(ownerId, "TEST-Q-2", Instant.now());
		analyze(owner, unsupported).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ANALYSIS_VERSION_UNSUPPORTED"));

		long done = survey(ownerId, "SIS-Q-1.1-100", Instant.now());
		JsonNode other = member();
		analyze(other, done).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		analyze(owner, Long.MAX_VALUE).andExpect(status().isNotFound());

		long resultId = requestAnalysis(owner, done);
		analyze(owner, done).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.resultId").value(resultId))
			.andExpect(jsonPath("$.sessionId").value(done))
			.andExpect(jsonPath("$.analysisVersion").value("SIS-A-1.0"))
			.andExpect(jsonPath("$.status").value("PENDING"));
		assertThat(jdbc.queryForObject("select count(*) from personality where survey_id=?", Long.class, done))
			.isEqualTo(1);

		result(other, resultId).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		result(owner, resultId).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.manifest.surveyVersion").value("SIS-Q-1.1-100"))
			.andExpect(jsonPath("$.scores").value(nullValue()))
			.andExpect(jsonPath("$.readings", hasSize(0)))
			.andExpect(jsonPath("$.attempts").doesNotExist());
	}

	@Test
	@DisplayName("분석이 성공하면 결과를 그대로 저장하고 적용 결과·설정 번호를 바꿔 설문 단계를 넘긴다")
	void succeeded() throws Exception {
		JsonNode tokens = member();
		long userId = userIdOf(tokens);
		long sessionId = survey(userId, "SIS-Q-1.1-100", Instant.now());
		long resultId = requestAnalysis(tokens, sessionId);
		long versionBefore = settingsVersion(userId);
		aiReturns(SUCCEEDED);

		worker.runDue();

		ArgumentCaptor<PersonalAnalysisClient.Request> sent = ArgumentCaptor.captor();
		verify(personalAnalysisClient).analyze(sent.capture(), anyString());
		assertThat(sent.getValue().surveyVersion()).isEqualTo("SIS-Q-1.1-100");
		assertThat(sent.getValue().analysisId()).isEqualTo(resultId);
		assertThat(sent.getValue().analysisVersion()).isEqualTo("SIS-A-1.0");
		assertThat(sent.getValue().answers()).extracting(PersonalAnalysisClient.Answer::questionId)
			.containsExactly("Q001", "Q002", "Q097", "Q098");
		assertThat(sent.getValue().answers().getFirst().value().asInt()).isEqualTo(4);

		assertThat(jdbc.queryForObject("select result->'typeRecommendations'->1->>'typeCode' from personality "
			+ "where id=?", String.class, resultId)).isEqualTo("WARMORBIT");
		assertThat(activePersonality(userId)).isEqualTo(resultId);
		assertThat(settingsVersion(userId)).isEqualTo(versionBefore + 1);
		// 새 설정 세대의 입력도 같은 트랜잭션에서 보존한다(만남 검증용).
		assertThat(jdbc.queryForObject("select count(*) from settings_snapshot where user_id=? and settings_version=?",
			Long.class, userId, versionBefore + 1)).isEqualTo(1);
		perform(get(me(tokens, "/onboarding")), tokens, null)
			.andExpect(jsonPath("$.nextStep").value("PREFERENCES_REQUIRED"));

		result(tokens, resultId).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.manifest.analysisVersion").value("SIS-A-1.0"))
			.andExpect(jsonPath("$.scores.I").value(75.0))
			.andExpect(jsonPath("$.typeStatus").value("ASSIGNED"))
			.andExpect(jsonPath("$.primaryType").value("EMBER"))
			.andExpect(jsonPath("$.readings", hasSize(3)))
			.andExpect(jsonPath("$.readings[0].templateId").value("PA-R-EXPRESSION-01"))
			.andExpect(jsonPath("$.readings[0].text").value(startsWith("관계를 시작하는")))
			.andExpect(jsonPath("$.readings[0].evidenceQuestionIds", contains("Q030", "Q032")))
			.andExpect(jsonPath("$.readings[0].priority").value(10))
			.andExpect(jsonPath("$.selfPreferenceDifferences[1].key").value("self-preference-Q030-Q065"))
			.andExpect(jsonPath("$.selfPreferenceDifferences[1].evidenceQuestionIds", contains("Q030", "Q065",
				"Q098")))
			.andExpect(jsonPath("$.selfPreferenceDifferences[1].priority").value(1))
			.andExpect(jsonPath("$.typeRecommendations", hasSize(3)))
			.andExpect(jsonPath("$.typeRecommendations[0].typeCode").value("SOFTTIDE"))
			.andExpect(jsonPath("$.typeRecommendations[0].score").value(83.33333333333333))
			.andExpect(jsonPath("$.typeRecommendations[0].mode").value("PREFERENCE_FIT"))
			.andExpect(jsonPath("$.typeRecommendations[0].evidenceQuestionIds", contains("Q065", "Q079", "Q096",
				"Q098")))
			.andExpect(jsonPath("$.missingQuestionIds", hasSize(0)));
	}

	@Test
	@DisplayName("Q98 미확인이나 Q97 엄격 제외의 허용 범위가 미확인이면 성공 결과도 저장만 하고 적용하지 않는다(SUR-02)")
	void reconfirmationHoldsActivation() throws Exception {
		for (String[] answers : new String[][] {{"Q098", "unknown"}, {"Q097", "unknown"}, {"Q097", "[\"smoking\"]"}}) {
			JsonNode tokens = member();
			long userId = userIdOf(tokens);
			long sessionId = survey(userId, "SIS-Q-1.1-100", Instant.now());
			boolean array = answers[1].startsWith("[");
			jdbc.update("update survey_answer set answer_value=?, answer_type=? where survey_id=? and question_id=?",
				answers[1], array ? "ARRAY" : "STRING", sessionId, answers[0]);
			if (array) {
				// 흡연 제외를 골랐는데 흡연 허용 범위(Q090)는 "상관없음"
				jdbc.update("insert into survey_answer(survey_id,question_id,answer_value,answer_type) "
					+ "values (?,'Q090','any','STRING')", sessionId);
			}
			long resultId = requestAnalysis(tokens, sessionId);
			long versionBefore = settingsVersion(userId);
			aiReturns(SUCCEEDED);

			worker.runDue();

			assertThat(jdbc.queryForObject("select status::text from personality where id=?", String.class,
				resultId)).as(answers[1]).isEqualTo("SUCCEEDED");
			assertThat(activePersonality(userId)).as(answers[1]).isNull();
			assertThat(settingsVersion(userId)).as(answers[1]).isEqualTo(versionBefore);
			// 다시 요청해도 재확인 전이면 적용하지 않는다.
			analyze(tokens, sessionId).andExpect(status().isAccepted());
			assertThat(activePersonality(userId)).as(answers[1]).isNull();
		}
	}

	@Test
	@DisplayName("적용이 거절돼도 성공 결과는 남고 AI를 다시 부르지 않는다")
	void rejectedApplicationKeepsResult() throws Exception {
		JsonNode tokens = member();
		long userId = userIdOf(tokens);
		long resultId = requestAnalysis(tokens, survey(userId, "SIS-Q-1.1-100", Instant.now()));
		// 설정 행이 없으면 적용 서비스가 SERVICE_UNAVAILABLE 로 거절한다.
		jdbc.update("delete from user_setting where user_id=?", userId);
		aiReturns(SUCCEEDED);

		worker.runDue();

		// 예전에는 결과 저장까지 되돌려져 PENDING 으로 남고 5분 뒤 AI를 다시 불렀다.
		assertThat(jdbc.queryForObject("select status::text from personality where id=?", String.class, resultId))
			.isEqualTo("SUCCEEDED");
		assertThat(activePersonality(userId)).isNull();
		verify(personalAnalysisClient, times(1)).analyze(any(), anyString());
	}

	@Test
	@DisplayName("적용이 일시 오류로 실패하면 다음 주기에 다시 적용하고, 기한이 지나면 그만둔다")
	void transientApplicationFailureIsRetried() throws Exception {
		JsonNode tokens = member();
		long userId = userIdOf(tokens);
		long resultId = requestAnalysis(tokens, survey(userId, "SIS-Q-1.1-100", Instant.now()));
		JsonNode late = member();
		long lateUserId = userIdOf(late);
		long lateResultId = requestAnalysis(late, survey(lateUserId, "SIS-Q-1.1-100", Instant.now()));
		aiReturns(SUCCEEDED);
		willThrow(new TransientDataAccessResourceException("일시 오류")).given(profileAnalysisApplications)
			.apply(anyLong(), anyLong(), any());

		worker.runDue();
		assertThat(activePersonality(userId)).isNull();
		assertThat(activePersonality(lateUserId)).isNull();

		// 첫 결과는 기한 안에 다시 적용된다.
		willCallRealMethod().given(profileAnalysisApplications).apply(anyLong(), anyLong(), any());
		worker.runDue();
		assertThat(activePersonality(userId)).isEqualTo(resultId);
		assertThat(activePersonality(lateUserId)).isEqualTo(lateResultId);
		verify(personalAnalysisClient, times(2)).analyze(any(), anyString());

		// 기한(stuckAfter)이 지나면 다시 시도하지 않는다.
		JsonNode expired = member();
		long expiredUserId = userIdOf(expired);
		long expiredSession = survey(expiredUserId, "SIS-Q-1.1-100", Instant.now());
		long expiredResult = requestAnalysis(expired, expiredSession);
		willThrow(new TransientDataAccessResourceException("일시 오류")).given(profileAnalysisApplications)
			.apply(anyLong(), anyLong(), any());
		worker.runDue();
		timeShift.set(Duration.ofMinutes(6));
		willCallRealMethod().given(profileAnalysisApplications).apply(anyLong(), anyLong(), any());
		worker.runDue();
		assertThat(activePersonality(expiredUserId)).isNull();

		// 기한이 지나도(재시작 포함) 같은 회차로 분석을 다시 요청하면 적용을 다시 시도한다. AI는 다시 부르지 않는다.
		analyze(expired, expiredSession).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.resultId").value(expiredResult))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"));
		assertThat(activePersonality(expiredUserId)).isEqualTo(expiredResult);
		long versionAfter = settingsVersion(expiredUserId);
		analyze(expired, expiredSession).andExpect(status().isAccepted());
		assertThat(settingsVersion(expiredUserId)).as("이미 적용된 결과는 다시 적용하지 않음").isEqualTo(versionAfter);
		verify(personalAnalysisClient, times(3)).analyze(any(), anyString());
	}

	@Test
	@DisplayName("응답 부족이면 결과는 저장하되 조회에는 부족한 문항만 주고, 적용 결과는 그대로 둔다")
	void insufficient() throws Exception {
		JsonNode tokens = member();
		long userId = userIdOf(tokens);
		long resultId = requestAnalysis(tokens, survey(userId, "SIS-Q-1.1-100", Instant.now()));
		aiReturns(INSUFFICIENT);

		worker.runDue();

		assertThat(jdbc.queryForObject("select result->'scores'->>'E' from personality where id=?", String.class,
			resultId)).isEqualTo("50.0");
		assertThat(activePersonality(userId)).isNull();
		result(tokens, resultId).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("INSUFFICIENT"))
			.andExpect(jsonPath("$.scores").value(nullValue()))
			.andExpect(jsonPath("$.typeStatus").value(nullValue()))
			.andExpect(jsonPath("$.readings", hasSize(0)))
			.andExpect(jsonPath("$.typeRecommendations", hasSize(0)))
			.andExpect(jsonPath("$.selfPreferenceDifferences", hasSize(0)))
			.andExpect(jsonPath("$.missingQuestionIds", hasSize(52)))
			.andExpect(jsonPath("$.missingQuestionIds[0]").value("Q002"));
	}

	@Test
	@DisplayName("점수가 없거나 요청과 다른 버전의 성공 응답은 저장·적용하지 않고 FAILED 로 끝낸다")
	void brokenSuccessIsRejected() throws Exception {
		JsonNode sample = jsonMapper.readTree(SUCCEEDED);
		for (String broken : new String[] {"{\"status\":\"SUCCEEDED\"}",
			((ObjectNode) sample.deepCopy()).put("analysisVersion", "SIS-A-9").toString(),
			((ObjectNode) sample.deepCopy()).put("surveyVersion", "SIS-Q-9").toString()}) {
			JsonNode tokens = member();
			long userId = userIdOf(tokens);
			long resultId = requestAnalysis(tokens, survey(userId, "SIS-Q-1.1-100", Instant.now()));
			aiReturns(broken);

			worker.runDue();

			assertThat(statusOf(resultId)).as(broken).isEqualTo("FAILED");
			assertThat(state(resultId).path("lastErrorCode").asString()).isEqualTo("AI_INVALID_RESPONSE");
			assertThat(state(resultId).has("nextRetryAt")).isFalse();
			assertThat(activePersonality(userId)).isNull();
		}
	}

	@Test
	@DisplayName("AI가 요청 오류(422)를 주면 다시 보내지 않고 FAILED 로 끝낸다")
	void clientErrorIsNotRetried() throws Exception {
		JsonNode tokens = member();
		long resultId = requestAnalysis(tokens, survey(userIdOf(tokens), "SIS-Q-1.1-100", Instant.now()));
		given(personalAnalysisClient.ready()).willReturn(true);
		given(personalAnalysisClient.analyze(any(), anyString()))
			.willThrow(new PersonalAnalysisClient.Failure("AI_HTTP_422", false));

		worker.runDue();
		timeShift.set(Duration.ofHours(1));
		assertThat(worker.runDue()).isZero();

		verify(personalAnalysisClient, times(1)).analyze(any(), anyString());
		assertThat(statusOf(resultId)).isEqualTo("FAILED");
		JsonNode state = state(resultId);
		assertThat(state.path("attempts").asInt()).isEqualTo(1);
		assertThat(state.path("lastErrorCode").asString()).isEqualTo("AI_HTTP_422");
		assertThat(state.has("nextRetryAt")).isFalse();
		timeShift.set(Duration.ZERO);
		result(tokens, resultId).andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.lastErrorCode").doesNotExist());

		// 끝난 실패는 다시 요청하면 같은 결과 번호로 처음부터 다시 시도한다
		long sessionId = jdbc.queryForObject("select survey_id from personality where id=?", Long.class, resultId);
		assertThat(requestAnalysis(tokens, sessionId)).isEqualTo(resultId);
		assertThat(state(resultId).path("attempts").asInt()).isZero();
		// 이미 예외를 던지도록 설정된 가짜라 willReturn 형태로 다시 설정한다
		willReturn(jsonMapper.readTree(SUCCEEDED)).given(personalAnalysisClient).analyze(any(), anyString());
		worker.runDue();
		assertThat(statusOf(resultId)).isEqualTo("SUCCEEDED");
	}

	@Test
	@DisplayName("일시 오류(503·시간 초과)는 기다렸다 다시 실행하고, 마지막 시도에서 성공하면 적용한다")
	void transientErrorIsRetried() throws Exception {
		JsonNode tokens = member();
		long userId = userIdOf(tokens);
		long resultId = requestAnalysis(tokens, survey(userId, "SIS-Q-1.1-100", Instant.now()));
		given(personalAnalysisClient.ready()).willReturn(true);
		given(personalAnalysisClient.analyze(any(), anyString()))
			.willThrow(new PersonalAnalysisClient.Failure("AI_HTTP_503", true))
			.willThrow(new PersonalAnalysisClient.Failure("AI_UNAVAILABLE", true))
			.willReturn(jsonMapper.readTree(SUCCEEDED));

		worker.runDue();
		assertThat(statusOf(resultId)).isEqualTo("FAILED");
		assertThat(state(resultId).has("nextRetryAt")).isTrue();
		// 재시도 시각 전에는 다시 실행하지 않는다
		assertThat(worker.runDue()).isZero();

		timeShift.set(Duration.ofMinutes(2));
		worker.runDue();
		assertThat(state(resultId).path("lastErrorCode").asString()).isEqualTo("AI_UNAVAILABLE");
		assertThat(state(resultId).path("attempts").asInt()).isEqualTo(2);

		timeShift.set(Duration.ofMinutes(4));
		worker.runDue();
		assertThat(statusOf(resultId)).isEqualTo("SUCCEEDED");
		assertThat(activePersonality(userId)).isEqualTo(resultId);
		verify(personalAnalysisClient, times(3)).analyze(any(), anyString());
	}

	@Test
	@DisplayName("이전 회차 분석이 늦게 성공해도 최근 회차의 적용 결과를 되돌리지 않는다")
	void olderSurveyDoesNotOverrideLatest() throws Exception {
		JsonNode tokens = member();
		long userId = userIdOf(tokens);
		long older = survey(userId, "SIS-Q-1.1-100", Instant.now().minus(Duration.ofHours(1)));
		long newer = survey(userId, "SIS-Q-1.1-100", Instant.now());
		long newerResult = requestAnalysis(tokens, newer);
		long olderResult = requestAnalysis(tokens, older);
		long versionBefore = settingsVersion(userId);
		aiReturns(SUCCEEDED);

		worker.runDue();

		assertThat(statusOf(olderResult)).isEqualTo("SUCCEEDED");
		assertThat(activePersonality(userId)).isEqualTo(newerResult);
		assertThat(settingsVersion(userId)).isEqualTo(versionBefore + 1);
	}

	@Test
	@DisplayName("가입 단계 조회로 제출한 회차·분석 결과·적용 결과를 다시 찾는다(재설치·다른 기기 복구)")
	void onboardingRecovery() throws Exception {
		JsonNode tokens = member();
		long userId = userIdOf(tokens);
		perform(get(me(tokens, "/onboarding")), tokens, null)
			.andExpect(jsonPath("$.latestCompletedSessionId").value(nullValue()))
			.andExpect(jsonPath("$.latestAnalysis").value(nullValue()))
			.andExpect(jsonPath("$.activeResultId").value(nullValue()));

		long first = survey(userId, "SIS-Q-1.1-100", Instant.now().minus(Duration.ofHours(1)));
		perform(get(me(tokens, "/onboarding")), tokens, null)
			.andExpect(jsonPath("$.latestCompletedSessionId").value(first))
			.andExpect(jsonPath("$.latestAnalysis").value(nullValue()));
		long firstResult = requestAnalysis(tokens, first);
		aiReturns(SUCCEEDED);
		worker.runDue();

		// 재검사: 새로 제출한 회차는 계산 중이고, 그 뒤에 또 작성 중 회차가 있다.
		long retest = survey(userId, "SIS-Q-1.1-100", Instant.now());
		long retestResult = requestAnalysis(tokens, retest);
		long draft = survey(userId, "SIS-Q-1.1-100", null);
		perform(get(me(tokens, "/onboarding")), tokens, null)
			.andExpect(jsonPath("$.surveySessionId").value(draft))
			.andExpect(jsonPath("$.latestCompletedSessionId").value(retest))
			.andExpect(jsonPath("$.latestAnalysis.resultId").value(retestResult))
			.andExpect(jsonPath("$.latestAnalysis.sessionId").value(retest))
			.andExpect(jsonPath("$.latestAnalysis.analysisVersion").value("SIS-A-1.0"))
			.andExpect(jsonPath("$.latestAnalysis.status").value("PENDING"))
			.andExpect(jsonPath("$.activeResultId").value(firstResult));
	}

	// 약관 동의·키링·프로필 등록까지 마친 회원(설문 단계)
	private JsonNode member() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		bindKeyring(userIdOf(tokens));
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
		completeProfile(tokens).andExpect(status().isOk());
		return tokens;
	}

	// 답 두 개가 있는 회차. completedAt 이 null 이면 작성 중 회차.
	private long survey(long userId, String version, Instant completedAt) {
		Long id = jdbc.queryForObject("insert into survey(user_id,survey_version,revision,completed_at) "
			+ "values (?,?,0,?) returning id", Long.class, userId, version,
			completedAt == null ? null : Timestamp.from(completedAt));
		jdbc.update("insert into survey_answer(survey_id,question_id,answer_value,answer_type) "
			+ "values (?,'Q002','unknown','STRING'), (?,'Q001','4','NUMBER'), (?,'Q097','any','STRING'), "
			+ "(?,'Q098','some','STRING')", id, id, id, id);
		return id;
	}

	private void aiReturns(String result) throws Exception {
		given(personalAnalysisClient.ready()).willReturn(true);
		given(personalAnalysisClient.analyze(any(), anyString())).willReturn(jsonMapper.readTree(result));
	}

	private ResultActions analyze(JsonNode tokens, long sessionId) throws Exception {
		return perform(post("/api/survey-sessions/" + sessionId + "/analyses"), tokens, null);
	}

	private long requestAnalysis(JsonNode tokens, long sessionId) throws Exception {
		String body = analyze(tokens, sessionId).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn().getResponse().getContentAsString();
		return jsonMapper.readTree(body).get("resultId").asLong();
	}

	private ResultActions result(JsonNode tokens, long resultId) throws Exception {
		return perform(get("/api/analysis-results/" + resultId), tokens, null);
	}

	private String statusOf(long resultId) {
		return jdbc.queryForObject("select status::text from personality where id=?", String.class, resultId);
	}

	private JsonNode state(long resultId) {
		return jsonMapper.readTree(jdbc.queryForObject("select result::text from personality where id=?",
			String.class, resultId));
	}

	private Long activePersonality(long userId) {
		return jdbc.queryForObject("select active_personality_id from profile where user_id=?", Long.class, userId);
	}

	private long settingsVersion(long userId) {
		return jdbc.queryForObject("select settings_version from user_setting where user_id=?", Long.class, userId);
	}
}
