package com.e106.tingle.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestProtectedController;
import com.e106.tingle.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;

/**
 * 설문 회차 시작·이어하기·조회와 가입 단계 조회의 설문 진행도.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import({TestcontainersConfiguration.class, TestProtectedController.class})
class SurveyIntegrationTest extends IntegrationTestSupport {

	private static final String SESSIONS = "/api/survey-sessions";
	private static final String SURVEY_VERSION = "SIS-Q-1.1-100";

	@Test
	@DisplayName("현재 설문은 100문항을 표시 순서대로 버전 정보와 함께 준다")
	void currentQuestionnaire() throws Exception {
		JsonNode tokens = registeredMember();
		perform(get("/api/surveys/current"), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.manifest.surveyVersion").value(SURVEY_VERSION))
			.andExpect(jsonPath("$.manifest.contentHash").isString())
			.andExpect(jsonPath("$.questionCount").value(100))
			.andExpect(jsonPath("$.questions", hasSize(100)))
			.andExpect(jsonPath("$.questions[0].questionId").value("Q001"))
			.andExpect(jsonPath("$.questions[99].questionId").value("Q100"))
			.andExpect(jsonPath("$.metadataSchema").doesNotExist());
	}

	@Test
	@DisplayName("회차를 시작하면 201과 빈 초안을 주고, 다시 시작하면 같은 초안을, 완료 뒤에는 새 회차를 준다")
	void startAndResume() throws Exception {
		JsonNode tokens = registeredMember();
		JsonNode first = json(start(tokens, SURVEY_VERSION).andExpect(status().isCreated())
			.andExpect(header().string("ETag", "\"0\""))
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.revision").value(0))
			.andExpect(jsonPath("$.answeredCount").value(0))
			.andExpect(jsonPath("$.questionCount").value(100))
			.andExpect(jsonPath("$.nextQuestionId").value("Q001"))
			.andExpect(jsonPath("$.sessionId").isNumber())
			.andExpect(jsonPath("$.answers", hasSize(0)))
			.andExpect(jsonPath("$.metadata").value(nullValue()))
			.andExpect(jsonPath("$.completedAt").value(nullValue())));
		long sessionId = first.get("sessionId").asLong();

		start(tokens, SURVEY_VERSION).andExpect(status().isCreated())
			.andExpect(jsonPath("$.sessionId").value(sessionId));
		assertThat(jdbc.queryForObject("select count(*) from survey where user_id=?", Long.class,
			userIdOf(tokens))).isEqualTo(1);

		perform(get(SESSIONS + "/" + sessionId), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId").value(sessionId));
		perform(get(SESSIONS + "/" + sessionId + "/questionnaire"), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.manifest.surveyVersion").value(SURVEY_VERSION))
			.andExpect(jsonPath("$.questions", hasSize(100)));
		perform(get(me(tokens, "/onboarding")), tokens, null)
			.andExpect(jsonPath("$.surveySessionId").value(sessionId))
			.andExpect(jsonPath("$.nextQuestionId").value("Q001"));

		// 완료한 회차가 있어도 다시 응답하려면 새 회차로 시작한다
		jdbc.update("update survey set completed_at=now() where id=?", sessionId);
		long second = json(start(tokens, SURVEY_VERSION).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("DRAFT"))).get("sessionId").asLong();
		assertThat(second).isNotEqualTo(sessionId);
	}

	@Test
	@DisplayName("저장된 응답으로 진행 정보를 계산하고, 응답은 표시 순서대로 준다")
	void progressFromStoredAnswers() throws Exception {
		JsonNode tokens = registeredMember();
		long sessionId = json(start(tokens, SURVEY_VERSION)).get("sessionId").asLong();
		jdbc.update("insert into survey_answer(survey_id,question_id,answer_value,answer_type) "
			+ "values (?,'Q002','unknown','STRING'), (?,'Q001','4','NUMBER')", sessionId, sessionId);

		perform(get(SESSIONS + "/" + sessionId), tokens, null)
			.andExpect(jsonPath("$.answeredCount").value(2))
			.andExpect(jsonPath("$.nextQuestionId").value("Q003"))
			.andExpect(jsonPath("$.answers[0].questionId").value("Q001"))
			.andExpect(jsonPath("$.answers[0].value").value(4))
			.andExpect(jsonPath("$.answers[1].value").value("unknown"));
	}

	@Test
	@DisplayName("프로필 등록 전에는 회차를 시작할 수 없고, 남의 회차·없는 회차는 404")
	void accessRules() throws Exception {
		JsonNode unregistered = login("kakao", newSocialId());
		agree(unregistered).andExpect(status().isOk());
		start(unregistered, SURVEY_VERSION).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));

		JsonNode owner = registeredMember();
		long sessionId = json(start(owner, SURVEY_VERSION)).get("sessionId").asLong();
		JsonNode other = registeredMember();
		perform(get(SESSIONS + "/" + sessionId), other, null).andExpect(status().isNotFound());
		perform(get(SESSIONS + "/" + Long.MAX_VALUE), owner, null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		perform(get(SESSIONS + "/0"), owner, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("설문 버전은 저장 한도인 30자까지 받고, 넘으면 400")
	void surveyVersionMaxLength() throws Exception {
		JsonNode tokens = registeredMember();
		start(tokens, "S".repeat(30)).andExpect(status().isNotFound());
		start(tokens, "S".repeat(31)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("같은 회원이 동시에 시작해도 작성 중 회차는 하나만 생긴다")
	void concurrentStartCreatesOneDraft() throws Exception {
		JsonNode tokens = registeredMember();
		List<Integer> statuses = runConcurrently(
			() -> start(tokens, SURVEY_VERSION).andReturn().getResponse().getStatus(),
			() -> start(tokens, SURVEY_VERSION).andReturn().getResponse().getStatus());
		assertThat(statuses).containsOnly(201);
		assertThat(jdbc.queryForObject("select count(*) from survey where user_id=?", Long.class,
			userIdOf(tokens))).isEqualTo(1);
	}

	@Test
	@DisplayName("답 저장은 덮어쓰고 배열 답은 배열로 돌려주며, 삭제는 미응답으로 되돌리고 없는 답 삭제도 200")
	void saveAndDeleteAnswers() throws Exception {
		JsonNode tokens = registeredMember();
		long sessionId = json(start(tokens, SURVEY_VERSION)).get("sessionId").asLong();

		saveAnswer(tokens, sessionId, "Q001", 4).andExpect(status().isOk())
			.andExpect(jsonPath("$.answeredCount").value(1))
			.andExpect(jsonPath("$.nextQuestionId").value("Q002"))
			.andExpect(jsonPath("$.answers[0].value").value(4));
		saveAnswer(tokens, sessionId, "Q001", 5.0).andExpect(status().isOk())
			.andExpect(jsonPath("$.answeredCount").value(1))
			.andExpect(jsonPath("$.answers[0].value").value(5));
		saveAnswer(tokens, sessionId, "Q040", List.of("rest", "talk")).andExpect(status().isOk())
			.andExpect(jsonPath("$.answers[1].questionId").value("Q040"))
			.andExpect(jsonPath("$.answers[1].value", hasSize(2)))
			.andExpect(jsonPath("$.answers[1].value[0]").value("rest"));
		assertThat(jdbc.queryForMap("select answer_value,answer_type,answered_at is not null as timed "
			+ "from survey_answer where survey_id=? and question_id='Q040'", sessionId))
			.containsEntry("answer_value", "[\"rest\",\"talk\"]").containsEntry("answer_type", "ARRAY")
			.containsEntry("timed", true);

		perform(delete(answerPath(sessionId, "Q001")), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.answeredCount").value(1))
			.andExpect(jsonPath("$.nextQuestionId").value("Q001"));
		perform(delete(answerPath(sessionId, "Q001")), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.answeredCount").value(1));
		perform(delete(answerPath(sessionId, "Q999")), tokens, null).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("문항 규칙에 맞지 않으면 422, 답 형식이 틀리면 400, 없는 문항·남의 회차는 404")
	void rejectsInvalidAnswers() throws Exception {
		JsonNode tokens = registeredMember();
		long sessionId = json(start(tokens, SURVEY_VERSION)).get("sessionId").asLong();

		for (Object invalid : List.<Object>of(6, "4", "any", List.of("rest", "rest"), List.of("rest", "unknown"))) {
			String question = invalid instanceof List ? "Q040" : "Q001";
			saveAnswer(tokens, sessionId, question, invalid).andExpect(status().is(422))
				.andExpect(jsonPath("$.code").value("INVALID_ANSWER"));
		}
		// 숫자 배열·아주 크거나 무한대로 읽히는 숫자도 500이 아니라 422
		for (String body : List.of("{\"value\":[1,2]}", "{\"value\":1e100}", "{\"value\":1e999}",
			"{\"value\":" + "9".repeat(200) + "}")) {
			String question = body.contains("[") ? "Q040" : "Q001";
			perform(put(answerPath(sessionId, question)), tokens, body).andExpect(status().is(422))
				.andExpect(jsonPath("$.code").value("INVALID_ANSWER"));
		}
		// 같은 값의 긴 소수 표기는 짧은 표기로 저장한다(저장 한도 100자 안)
		perform(put(answerPath(sessionId, "Q002")), tokens, "{\"value\":4." + "0".repeat(200) + "}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.answers[0].value").value(4));
		perform(delete(answerPath(sessionId, "Q002")), tokens, null).andExpect(status().isOk());
		saveAnswer(tokens, sessionId, "Q061", "any").andExpect(status().isOk());
		for (String body : List.of("{}", "{\"value\":null}", "{\"value\":true}", "{\"value\":{\"a\":1}}")) {
			perform(put(answerPath(sessionId, "Q001")), tokens, body).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
		saveAnswer(tokens, sessionId, "Q999", 4).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		saveAnswer(registeredMember(), sessionId, "Q001", 4).andExpect(status().isNotFound());
		perform(post(SESSIONS + "/" + sessionId + "/complete"), registeredMember(), null)
			.andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select count(*) from survey_answer where survey_id=?", Long.class,
			sessionId)).isEqualTo(1);
	}

	@Test
	@DisplayName("모든 문항에 답해야 완료되고(unknown 도 답), 반복 완료는 같은 결과, 완료 뒤 저장·삭제는 409")
	void completeRequiresAllAnswers() throws Exception {
		JsonNode tokens = registeredMember();
		long sessionId = json(start(tokens, SURVEY_VERSION)).get("sessionId").asLong();
		String complete = SESSIONS + "/" + sessionId + "/complete";
		jdbc.update("insert into survey_answer(survey_id,question_id,answer_value,answer_type) "
			+ "select ?, 'Q' || lpad(g::text, 3, '0'), 'unknown', 'STRING' from generate_series(2, 100) g", sessionId);

		perform(post(complete), tokens, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ANSWERS_INCOMPLETE"));
		// 옛 자료처럼 규칙에 맞지 않는 값(숫자 문항에 문자열 "4")이 저장돼 있으면 답한 것으로 세지 않는다
		jdbc.update("insert into survey_answer(survey_id,question_id,answer_value,answer_type) "
			+ "values (?,'Q001','4','STRING')", sessionId);
		perform(post(complete), tokens, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ANSWERS_INCOMPLETE"));
		saveAnswer(tokens, sessionId, "Q001", 3).andExpect(status().isOk());

		JsonNode completed = json(perform(post(complete), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.answeredCount").value(100))
			.andExpect(jsonPath("$.nextQuestionId").value(nullValue())));
		perform(post(complete), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.completedAt").value(completed.get("completedAt").asString()));

		saveAnswer(tokens, sessionId, "Q001", 4).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SESSION_COMPLETED"));
		perform(delete(answerPath(sessionId, "Q001")), tokens, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SESSION_COMPLETED"));
	}

	@Test
	@DisplayName("완료와 답 저장이 동시에 와도 완료 뒤에 저장된 답은 없다(회차 행 잠금)")
	void concurrentSavesNeverLandAfterCompletion() throws Exception {
		JsonNode tokens = registeredMember();
		long sessionId = json(start(tokens, SURVEY_VERSION)).get("sessionId").asLong();
		jdbc.update("insert into survey_answer(survey_id,question_id,answer_value,answer_type,answered_at) "
			+ "select ?, 'Q' || lpad(g::text, 3, '0'), 'unknown', 'STRING', now() - interval '1 day' "
			+ "from generate_series(1, 100) g",
			sessionId);

		List<Integer> codes = runConcurrently(
			() -> perform(post(SESSIONS + "/" + sessionId + "/complete"), tokens, null).andReturn().getResponse()
				.getStatus(),
			() -> saveAnswer(tokens, sessionId, "Q001", "unknown").andReturn().getResponse().getStatus(),
			() -> saveAnswer(tokens, sessionId, "Q002", "unknown").andReturn().getResponse().getStatus(),
			() -> saveAnswer(tokens, sessionId, "Q003", "unknown").andReturn().getResponse().getStatus(),
			() -> saveAnswer(tokens, sessionId, "Q004", "unknown").andReturn().getResponse().getStatus());

		assertThat(codes.getFirst()).isEqualTo(200);
		assertThat(codes.subList(1, codes.size())).allMatch(code -> code == 200 || code == 409);
		assertThat(jdbc.queryForObject("select count(*) from survey_answer a join survey s on s.id=a.survey_id "
			+ "where s.id=? and a.answered_at > s.completed_at", Long.class, sessionId)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from survey_answer where survey_id=?", Long.class,
			sessionId)).isEqualTo(100);
	}

	@Test
	@DisplayName("이관 미리보기는 규칙 파일대로 문항을 나누고, 이관은 원본을 그대로 둔 채 새 버전 회차에 옮길 답만 복사한다")
	void migratesToNewVersion() throws Exception {
		JsonNode tokens = registeredMember();
		long source = json(start(tokens, SURVEY_VERSION)).get("sessionId").asLong();
		saveAnswer(tokens, source, "Q001", 4).andExpect(status().isOk());
		saveAnswer(tokens, source, "Q002", 3).andExpect(status().isOk());
		saveAnswer(tokens, source, "Q003", 5).andExpect(status().isOk());
		// 새 버전 Q040 은 하나만 고를 수 있어 두 개 고른 답은 옮길 수 없다
		saveAnswer(tokens, source, "Q040", List.of("rest", "talk")).andExpect(status().isOk());
		String plan = SESSIONS + "/" + source + "/migration-plan?targetSurveyVersion=";

		JsonNode preview = json(perform(get(plan + "TEST-Q-2"), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.sourceSessionId").value(source))
			.andExpect(jsonPath("$.targetSurveyVersion").value("TEST-Q-2"))
			.andExpect(jsonPath("$.preservedQuestionIds", contains("Q001")))
			.andExpect(jsonPath("$.reconfirmQuestionIds", contains("Q002")))
			.andExpect(jsonPath("$.incompatibleQuestionIds", contains("Q003", "Q040")))
			.andExpect(jsonPath("$.policyHash").isString()));
		String policyHash = preview.get("policyHash").asString();

		perform(get(plan + SURVEY_VERSION), tokens, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("MIGRATION_UNSUPPORTED"));
		perform(get(plan + "SIS-Q-0.9"), tokens, null).andExpect(status().isConflict());
		perform(get(SESSIONS + "/" + source + "/migration-plan"), tokens, null).andExpect(status().isBadRequest());
		perform(get(plan + "TEST-Q-2"), registeredMember(), null).andExpect(status().isNotFound());
		migrate(tokens, source, "TEST-Q-2", "stale").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("MIGRATION_POLICY_CHANGED"));

		long migrated = json(migrate(tokens, source, "TEST-Q-2", policyHash).andExpect(status().isCreated())
			.andExpect(jsonPath("$.manifest.surveyVersion").value("TEST-Q-2"))
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.questionCount").value(3))
			.andExpect(jsonPath("$.answeredCount").value(1))
			.andExpect(jsonPath("$.answers[0].questionId").value("Q001"))
			.andExpect(jsonPath("$.answers[0].value").value(4))
			.andExpect(jsonPath("$.nextQuestionId").value("Q002"))).get("sessionId").asLong();
		assertThat(migrated).isNotEqualTo(source);
		// 원본 답은 그대로이고, 다시 이관하면 같은 버전의 작성 중 회차를 돌려준다
		perform(get(SESSIONS + "/" + source), tokens, null)
			.andExpect(jsonPath("$.manifest.surveyVersion").value(SURVEY_VERSION))
			.andExpect(jsonPath("$.answeredCount").value(4));
		migrate(tokens, source, "TEST-Q-2", policyHash).andExpect(status().isCreated())
			.andExpect(jsonPath("$.sessionId").value(migrated));
	}

	@Test
	@DisplayName("선택 나이 정보는 If-Match 가 맞을 때만 저장하고 revision 을 올리며, 프로필·희망 조건은 바꾸지 않는다")
	void savesMetadataWithRevision() throws Exception {
		JsonNode tokens = registeredMember();
		long userId = userIdOf(tokens);
		long sessionId = json(start(tokens, SURVEY_VERSION)).get("sessionId").asLong();
		Map<String, Object> before = profileAndPreferences(userId);

		saveMetadata(tokens, sessionId, metadata(26, 25, 32), "\"0\"").andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"1\""))
			.andExpect(jsonPath("$.revision").value(1))
			.andExpect(jsonPath("$.metadata.ownAge").value(26))
			.andExpect(jsonPath("$.metadata.desiredAgeMin").value(25))
			.andExpect(jsonPath("$.metadata.desiredAgeMax").value(32));
		perform(get(SESSIONS + "/" + sessionId), tokens, null).andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"1\""))
			.andExpect(jsonPath("$.metadata.desiredAgeMax").value(32));
		// 옛 ETag 로 저장하면 412, If-Match 가 없으면 428 이고 저장 값은 그대로다
		saveMetadata(tokens, sessionId, metadata(30, null, null), "\"0\"").andExpect(status().is(412))
			.andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
		saveMetadata(tokens, sessionId, metadata(30, null, null), null).andExpect(status().is(428))
			.andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
		perform(get(SESSIONS + "/" + sessionId), tokens, null)
			.andExpect(jsonPath("$.revision").value(1))
			.andExpect(jsonPath("$.metadata.ownAge").value(26));
		// 모르는 값은 null 로 비울 수 있다
		saveMetadata(tokens, sessionId, metadata(null, 19, null), "\"1\"").andExpect(status().isOk())
			.andExpect(jsonPath("$.revision").value(2))
			.andExpect(jsonPath("$.metadata.ownAge").value(nullValue()))
			.andExpect(jsonPath("$.metadata.desiredAgeMin").value(19));
		assertThat(jdbc.queryForObject("select response_context->'metadata'->>'desiredAgeMin' from survey where id=?",
			String.class, sessionId)).isEqualTo("19");
		assertThat(profileAndPreferences(userId)).isEqualTo(before);
	}

	@Test
	@DisplayName("옛 회차의 response_context 에 있던 다른 키는 두고 metadata 만 바꾼다")
	void keepsLegacyContextKeys() throws Exception {
		JsonNode tokens = registeredMember();
		long sessionId = json(start(tokens, SURVEY_VERSION)).get("sessionId").asLong();
		jdbc.update("update survey set response_context=cast(? as jsonb) where id=?",
			"{\"metadata\":{\"ownAge\":25},\"audit\":{\"old\":\"keep\"}}", sessionId);

		perform(get(SESSIONS + "/" + sessionId), tokens, null).andExpect(jsonPath("$.metadata.ownAge").value(25))
			.andExpect(jsonPath("$.audit").doesNotExist());
		saveMetadata(tokens, sessionId, metadata(27, null, null), "\"0\"").andExpect(status().isOk())
			.andExpect(jsonPath("$.metadata.ownAge").value(27));
		assertThat(jdbc.queryForObject("select response_context->'audit'->>'old' from survey where id=?",
			String.class, sessionId)).isEqualTo("keep");
	}

	@Test
	@DisplayName("나이 정보가 19~100 정수·null 이 아니거나 최소가 최대보다 크면 422, 완료 회차는 409, 남의·없는 회차는 404")
	void rejectsInvalidMetadata() throws Exception {
		JsonNode tokens = registeredMember();
		long sessionId = json(start(tokens, SURVEY_VERSION)).get("sessionId").asLong();
		String path = SESSIONS + "/" + sessionId + "/metadata";
		for (String body : List.of("{\"ownAge\":18,\"desiredAgeMin\":null,\"desiredAgeMax\":null}",
			"{\"ownAge\":101,\"desiredAgeMin\":null,\"desiredAgeMax\":null}",
			"{\"ownAge\":26.5,\"desiredAgeMin\":null,\"desiredAgeMax\":null}",
			"{\"ownAge\":\"26\",\"desiredAgeMin\":null,\"desiredAgeMax\":null}",
			"{\"ownAge\":" + "9".repeat(30) + ",\"desiredAgeMin\":null,\"desiredAgeMax\":null}",
			"{\"ownAge\":26,\"desiredAgeMin\":33,\"desiredAgeMax\":32}",
			"{\"ownAge\":26,\"desiredAgeMin\":25}",
			"{\"ownAge\":26,\"desiredAgeMin\":25,\"desiredAgeMax\":32,\"extra\":1}")) {
			perform(put(path).header("If-Match", "\"0\""), tokens, body).andExpect(status().is(422))
				.andExpect(jsonPath("$.code").value("INVALID_ANSWER"));
		}
		perform(put(path).header("If-Match", "\"0\""), tokens, "[26]").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		saveMetadata(registeredMember(), sessionId, metadata(26, 25, 32), "\"0\"").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		saveMetadata(tokens, Long.MAX_VALUE, metadata(26, 25, 32), "\"0\"").andExpect(status().isNotFound());
		assertThat(jdbc.queryForMap("select revision, response_context from survey where id=?", sessionId))
			.containsEntry("revision", 0L).containsEntry("response_context", null);

		jdbc.update("update survey set completed_at=now() where id=?", sessionId);
		saveMetadata(tokens, sessionId, metadata(26, 25, 32), "\"0\"").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SESSION_COMPLETED"));
	}

	private ResultActions saveMetadata(JsonNode tokens, long sessionId, Map<String, Object> body, String ifMatch)
		throws Exception {
		MockHttpServletRequestBuilder request = put(SESSIONS + "/" + sessionId + "/metadata");
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return perform(request, tokens, toJson(body));
	}

	private static Map<String, Object> metadata(Integer ownAge, Integer desiredAgeMin, Integer desiredAgeMax) {
		Map<String, Object> body = new HashMap<>();
		body.put("ownAge", ownAge);
		body.put("desiredAgeMin", desiredAgeMin);
		body.put("desiredAgeMax", desiredAgeMax);
		return body;
	}

	private Map<String, Object> profileAndPreferences(long userId) {
		Map<String, Object> state = new HashMap<>(jdbc.queryForMap("select * from profile where user_id=?", userId));
		state.put("preference", jdbc.queryForList("select * from matching_preference where user_id=?", userId));
		return state;
	}

	private ResultActions migrate(JsonNode tokens, long sessionId, String target, String policyHash)
		throws Exception {
		return perform(post(SESSIONS + "/" + sessionId + "/migrations"), tokens,
			toJson(Map.of("targetSurveyVersion", target, "policyHash", policyHash)));
	}

	private ResultActions saveAnswer(JsonNode tokens, long sessionId, String questionId, Object value)
		throws Exception {
		return perform(put(answerPath(sessionId, questionId)), tokens, toJson(Map.of("value", value)));
	}

	private static String answerPath(long sessionId, String questionId) {
		return SESSIONS + "/" + sessionId + "/answers/" + questionId;
	}

	private JsonNode registeredMember() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
		completeProfile(tokens).andExpect(status().isOk());
		return tokens;
	}

	private ResultActions start(JsonNode tokens, String version) throws Exception {
		return perform(post(SESSIONS), tokens,
			toJson(Map.of("surveyVersion", version)));
	}

	private JsonNode json(ResultActions result) throws Exception {
		return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}
}
