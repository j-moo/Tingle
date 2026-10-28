package com.e106.tingle.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

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
	@DisplayName("현재 설문은 100문항을 표시 순서대로, 버전 정보와 나이 입력 스키마와 함께 준다")
	void currentQuestionnaire() throws Exception {
		JsonNode tokens = registeredMember();
		perform(get("/api/surveys/current"), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.manifest.surveyVersion").value(SURVEY_VERSION))
			.andExpect(jsonPath("$.manifest.contentHash").isString())
			.andExpect(jsonPath("$.questionCount").value(100))
			.andExpect(jsonPath("$.questions", hasSize(100)))
			.andExpect(jsonPath("$.questions[0].questionId").value("Q001"))
			.andExpect(jsonPath("$.questions[99].questionId").value("Q100"))
			.andExpect(jsonPath("$.metadataSchema.required", hasSize(3)));
	}

	@Test
	@DisplayName("회차를 시작하면 201·ETag 와 빈 초안을 주고, 다시 시작하면 같은 초안을 이어간다")
	void startAndResume() throws Exception {
		JsonNode tokens = registeredMember();
		JsonNode first = json(start(tokens, UUID.randomUUID(), SURVEY_VERSION).andExpect(status().isCreated())
			.andExpect(header().string("ETag", "\"0\""))
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.revision").value(0))
			.andExpect(jsonPath("$.answeredCount").value(0))
			.andExpect(jsonPath("$.questionCount").value(100))
			.andExpect(jsonPath("$.nextQuestionId").value("Q001"))
			.andExpect(jsonPath("$.sessionId").isNumber())
			.andExpect(jsonPath("$.answers", hasSize(0)))
			.andExpect(jsonPath("$.metadata.ownAge").value(nullValue()))
			.andExpect(jsonPath("$.completedAt").value(nullValue())));
		long sessionId = first.get("sessionId").asLong();

		// 다른 Idempotency-Key 로 다시 시작해도 작성 중 회차를 이어가고, 변경 번호는 그대로다
		UUID resumeKey = UUID.randomUUID();
		start(tokens, resumeKey, SURVEY_VERSION).andExpect(status().isCreated())
			.andExpect(header().string("ETag", "\"0\""))
			.andExpect(jsonPath("$.sessionId").value(sessionId));
		assertThat(jdbc.queryForObject("select count(*) from survey where user_id=?", Long.class,
			userIdOf(tokens))).isEqualTo(1);

		perform(get(SESSIONS + "/" + sessionId), tokens, null).andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"0\""))
			.andExpect(jsonPath("$.sessionId").value(sessionId));
		perform(get(SESSIONS + "/" + sessionId + "/questionnaire"), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.manifest.surveyVersion").value(SURVEY_VERSION))
			.andExpect(jsonPath("$.questions", hasSize(100)));
		perform(get(me(tokens, "/onboarding")), tokens, null)
			.andExpect(jsonPath("$.surveySessionId").value(sessionId))
			.andExpect(jsonPath("$.nextQuestionId").value("Q001"));

		// 이어간 요청의 키로 완료 뒤 재시도해도 같은 회차를 준다
		jdbc.update("update survey set completed_at=now() where id=?", sessionId);
		start(tokens, resumeKey, SURVEY_VERSION).andExpect(status().isCreated())
			.andExpect(jsonPath("$.sessionId").value(sessionId))
			.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	@DisplayName("저장된 응답으로 진행 정보를 계산하고, 응답은 표시 순서대로 준다")
	void progressFromStoredAnswers() throws Exception {
		JsonNode tokens = registeredMember();
		long sessionId = json(start(tokens, UUID.randomUUID(), SURVEY_VERSION)).get("sessionId").asLong();
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
	@DisplayName("같은 Idempotency-Key 는 처음 회차를 다시 주고, 다른 버전이면 409")
	void idempotentStart() throws Exception {
		JsonNode tokens = registeredMember();
		UUID key = UUID.randomUUID();
		long sessionId = json(start(tokens, key, SURVEY_VERSION)).get("sessionId").asLong();
		// 완료된 뒤에 같은 키로 다시 와도 새 회차를 만들지 않는다
		jdbc.update("update survey set completed_at=now() where id=?", sessionId);

		start(tokens, key, SURVEY_VERSION).andExpect(status().isCreated())
			.andExpect(jsonPath("$.sessionId").value(sessionId))
			.andExpect(jsonPath("$.status").value("COMPLETED"));
		start(tokens, key, "SIS-Q-0.9").andExpect(status().isNotFound());
		jdbc.update("update survey set survey_version='SIS-Q-OLD' where id=?",
			sessionId);
		start(tokens, key, SURVEY_VERSION).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
	}

	@Test
	@DisplayName("프로필 등록 전에는 회차를 시작할 수 없고, 남의 회차·없는 회차는 404")
	void accessRules() throws Exception {
		JsonNode unregistered = login("kakao", newSocialId());
		agree(unregistered).andExpect(status().isOk());
		start(unregistered, UUID.randomUUID(), SURVEY_VERSION).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));

		JsonNode owner = registeredMember();
		long sessionId = json(start(owner, UUID.randomUUID(), SURVEY_VERSION)).get("sessionId").asLong();
		JsonNode other = registeredMember();
		perform(get(SESSIONS + "/" + sessionId), other, null).andExpect(status().isNotFound());
		perform(get(SESSIONS + "/" + Long.MAX_VALUE), owner, null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		perform(post(SESSIONS), owner, toJson(Map.of("surveyVersion", SURVEY_VERSION)))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("설문 버전은 저장 한도인 30자까지 받고, 넘으면 400")
	void surveyVersionMaxLength() throws Exception {
		JsonNode tokens = registeredMember();
		start(tokens, UUID.randomUUID(), "S".repeat(30)).andExpect(status().isNotFound());
		start(tokens, UUID.randomUUID(), "S".repeat(31)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("같은 회원이 동시에 시작해도 작성 중 회차는 하나만 생긴다")
	void concurrentStartCreatesOneDraft() throws Exception {
		JsonNode tokens = registeredMember();
		List<Integer> statuses = runConcurrently(
			() -> start(tokens, UUID.randomUUID(), SURVEY_VERSION).andReturn().getResponse().getStatus(),
			() -> start(tokens, UUID.randomUUID(), SURVEY_VERSION).andReturn().getResponse().getStatus());
		assertThat(statuses).containsOnly(201);
		assertThat(jdbc.queryForObject("select count(*) from survey where user_id=?", Long.class,
			userIdOf(tokens))).isEqualTo(1);
	}

	private JsonNode registeredMember() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
		completeProfile(tokens).andExpect(status().isOk());
		return tokens;
	}

	private ResultActions start(JsonNode tokens, UUID key, String version) throws Exception {
		return perform(post(SESSIONS).header("Idempotency-Key", key), tokens,
			toJson(Map.of("surveyVersion", version)));
	}

	private JsonNode json(ResultActions result) throws Exception {
		return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}
}
