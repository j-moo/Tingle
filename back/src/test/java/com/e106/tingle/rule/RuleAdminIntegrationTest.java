package com.e106.tingle.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 운영자 규칙 관리: 초안·조합·검증·승인·게시·새 회차 기본 조합 전환.
 * 분석이 받는 설문 버전 목록에 테스트용 버전을 넣어 둔다(검증의 ANALYSIS_SUPPORTS_SURVEY 검사).
 */
@SpringBootTest(properties = "tingle.ai.personal-analysis.survey-versions="
	+ "SIS-Q-1.1-100,RQ-LIFE,RQ-FLOW,RQ-RACE,RQ-CH-A,RQ-CH-B,RQ-MIG,RQ-REAL,RQ-SAME")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class RuleAdminIntegrationTest extends IntegrationTestSupport {

	private static final String ARTIFACTS = "/api/admin/v1/rule-artifacts";
	private static final String RELEASES = "/api/admin/v1/releases";
	private static final String CHANNEL = "/api/admin/v1/release-channels/current";
	private static final String FILE_VERSION = "SIS-Q-1.1-100";

	@Test
	@DisplayName("운영자가 아닌 회원은 403, 토큰이 없으면 401")
	void onlyOperators() throws Exception {
		JsonNode member = login("kakao", newSocialId());
		perform(post(ARTIFACTS), member, toJson(input("RQ-NONE", questionnaire("RQ-NONE"))))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		perform(get(CHANNEL), member, null).andExpect(status().isForbidden());
		mockMvc.perform(get(CHANNEL)).andExpect(status().isUnauthorized());
		// 운영자는 약관 동의 전(가입 단계와 무관)이어도 쓸 수 있다(/api/admin/** 은 가입 단계 검사 제외).
		perform(get(CHANNEL), operator(), null).andExpect(status().isOk());
	}

	@Test
	@DisplayName("초안은 형식 검사·버전 중복·If-Match 조건부 수정을 거치고, 수정 이유는 기록에 남는다")
	void artifactDraftLifecycle() throws Exception {
		JsonNode op = operator();
		JsonNode created = json(perform(post(ARTIFACTS), op, toJson(input("RQ-LIFE", questionnaire("RQ-LIFE"))))
			.andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
			.andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.revision").value(0))
			.andExpect(jsonPath("$.contentHash").isString()));
		String id = created.get("artifactId").asString();

		// 같은 종류·버전, 서버 파일 설문 버전과 같은 이름은 409
		perform(post(ARTIFACTS), op, toJson(input("RQ-LIFE", questionnaire("RQ-LIFE"))))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RULE_VERSION_CONFLICT"));
		perform(post(ARTIFACTS), op, toJson(input(FILE_VERSION, questionnaire(FILE_VERSION))))
			.andExpect(status().isConflict());

		// 형식 오류는 맞지 않는 칸 위치를 fields 로 준다. 등록되지 않은 응답 규칙(임의 키)도 거절한다.
		ObjectNode bad = questionnaire("RQ-BAD");
		((ObjectNode) bad.get("questions").get(0).get("responseSchema")).put("$ref", "http://evil");
		bad.put("questionCount", 5);
		perform(post(ARTIFACTS), op, toJson(input("RQ-BAD", bad))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_RULE_DOCUMENT"))
			.andExpect(jsonPath("$.fields", hasItem("document.questionCount")))
			.andExpect(jsonPath("$.fields", hasItem("document.questions[0].responseSchema.$ref")));
		// 형식이 등록되지 않은 종류는 422
		Map<String, Object> scoring = new HashMap<>(input("RQ-SCORE", questionnaire("RQ-SCORE")));
		scoring.put("kind", "SCORING");
		perform(post(ARTIFACTS), op, toJson(scoring)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("POLICY_UNRESOLVED"));

		Map<String, Object> edit = input("RQ-LIFE", questionnaire("RQ-LIFE"));
		edit.put("changeReason", "문구 수정");
		perform(put(ARTIFACTS + "/" + id), op, toJson(edit)).andExpect(status().isPreconditionRequired());
		perform(put(ARTIFACTS + "/" + id).header("If-Match", "\"7\""), op, toJson(edit))
			.andExpect(status().isPreconditionFailed());
		perform(put(ARTIFACTS + "/" + id).header("If-Match", "\"0\""), op, toJson(edit))
			.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
			.andExpect(jsonPath("$.revision").value(1));
		perform(get(ARTIFACTS + "/" + id), op, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.document.surveyVersion").value("RQ-LIFE"));
		assertThat(jdbc.queryForList("select action from rule_audit where target_id=?::uuid order by id",
			String.class, id)).containsExactly("ARTIFACT_CREATE", "ARTIFACT_UPDATE");
		assertThat(jdbc.queryForObject("select reason from rule_audit where target_id=?::uuid "
			+ "and action='ARTIFACT_UPDATE'", String.class, id)).isEqualTo("문구 수정");
	}

	@Test
	@DisplayName("작성자는 승인할 수 없고, 초안을 고치면 검증·승인이 무효가 되며, 게시한 뒤에는 바꿀 수 없다")
	void validateApprovePublish() throws Exception {
		JsonNode author = operator();
		JsonNode reviewer = operator();
		String artifactId = createQuestionnaire(author, "RQ-FLOW").get("artifactId").asString();
		String releaseId = createRelease(author, artifactId).get("releaseId").asString();

		JsonNode validation = validate(author, releaseId);
		assertThat(validation.get("status").asString()).isEqualTo("PASSED");
		assertThat(validation.get("checks").findValuesAsString("code")).contains("ENGINE_VERSION", "SCHEMA",
			"SURVEY_VERSION_UNIQUE", "ANALYSIS_SUPPORTS_SURVEY", "ANSWER_FIXTURES");

		approve(author, releaseId, validation).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"));
		publish(author, releaseId, validation).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPROVAL_REQUIRED"));
		approve(reviewer, releaseId, validation).andExpect(status().isCreated())
			.andExpect(jsonPath("$.reviewerSubject").value(String.valueOf(userIdOf(reviewer))))
			.andExpect(jsonPath("$.candidateHash").value(validation.get("candidateHash").asString()));
		perform(get(RELEASES + "/" + releaseId), author, null).andExpect(jsonPath("$.status").value("APPROVED"));

		// 리뷰어가 초안을 고치면 해시가 바뀌어 이전 검증·승인이 무효가 되고, 리뷰어도 작성자가 된다.
		ObjectNode changed = questionnaire("RQ-FLOW");
		((ObjectNode) changed.get("questions").get(0)).put("text", "바뀐 문구");
		perform(put(ARTIFACTS + "/" + artifactId).header("If-Match", "\"0\""), reviewer,
			toJson(input("RQ-FLOW", changed))).andExpect(status().isOk());
		perform(get(RELEASES + "/" + releaseId), author, null).andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.approval").value(nullValue()));
		publish(author, releaseId, validation).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VALIDATION_STALE"));
		JsonNode revalidated = validate(author, releaseId);
		approve(reviewer, releaseId, revalidated).andExpect(status().isForbidden());
		JsonNode third = operator();
		approve(third, releaseId, validation).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VALIDATION_STALE"));
		approve(third, releaseId, revalidated).andExpect(status().isCreated());

		publish(author, releaseId, revalidated).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.publishedAt").isString());
		publish(author, releaseId, revalidated).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IMMUTABLE_VERSION"));
		perform(get(ARTIFACTS + "/" + artifactId), author, null).andExpect(jsonPath("$.status").value("PUBLISHED"));
		perform(put(ARTIFACTS + "/" + artifactId).header("If-Match", "\"1\""), author,
			toJson(input("RQ-FLOW", questionnaire("RQ-FLOW")))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IMMUTABLE_VERSION"));
		perform(post(RELEASES + "/" + releaseId + "/validate"), author, null).andExpect(status().isConflict());
		assertThat(jdbc.queryForList("select action from rule_audit where target_id=?::uuid order by id",
			String.class, releaseId)).containsExactly("RELEASE_CREATE", "RELEASE_VALIDATE", "RELEASE_APPROVE",
			"RELEASE_VALIDATE", "RELEASE_APPROVE", "RELEASE_PUBLISH");
	}

	@Test
	@DisplayName("승인 뒤 초안을 같은 내용으로 다시 저장해도 승인·검증이 무효가 된다")
	void anyEditInvalidatesApproval() throws Exception {
		JsonNode author = operator();
		String artifactId = createQuestionnaire(author, "RQ-SAME").get("artifactId").asString();
		String releaseId = createRelease(author, artifactId).get("releaseId").asString();
		JsonNode validation = validate(author, releaseId);
		approve(operator(), releaseId, validation).andExpect(status().isCreated());
		perform(put(ARTIFACTS + "/" + artifactId).header("If-Match", "\"0\""), author,
			toJson(input("RQ-SAME", questionnaire("RQ-SAME")))).andExpect(status().isOk());
		perform(get(RELEASES + "/" + releaseId), author, null).andExpect(jsonPath("$.status").value("DRAFT"));
		publish(author, releaseId, validation).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VALIDATION_STALE"));
	}

	@Test
	@DisplayName("같은 조합을 동시에 게시하면 하나만 성공하고 나머지는 409")
	void concurrentPublish() throws Exception {
		JsonNode author = operator();
		JsonNode reviewer = operator();
		String releaseId = createRelease(author,
			createQuestionnaire(author, "RQ-RACE").get("artifactId").asString()).get("releaseId").asString();
		JsonNode validation = validate(author, releaseId);
		approve(reviewer, releaseId, validation).andExpect(status().isCreated());

		List<Integer> statuses = runConcurrently(
			() -> publish(author, releaseId, validation).andReturn().getResponse().getStatus(),
			() -> publish(reviewer, releaseId, validation).andReturn().getResponse().getStatus());
		assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		assertThat(jdbc.queryForObject("select count(*) from rule_audit where target_id=?::uuid "
			+ "and action='RELEASE_PUBLISH'", Integer.class, releaseId)).isEqualTo(1);
	}

	@Test
	@DisplayName("기본 조합 전환·되돌리기는 새 회차에만 적용되고, 이미 시작한 회차는 시작 때 버전을 계속 쓴다")
	void channelSwitchAffectsOnlyNewSessions() throws Exception {
		JsonNode author = operator();
		JsonNode reviewer = operator();
		String releaseA = publishedRelease(author, reviewer, "RQ-CH-A");
		String releaseB = publishedRelease(author, reviewer, "RQ-CH-B");
		JsonNode member = registeredMember();

		String previous = json(perform(get(CHANNEL), author, null).andExpect(status().isOk())).get("releaseId")
			.asString(null);
		long fileSession = startSession(member, currentVersion(member));

		// 잘못된 이전 값·게시 안 된 조합은 거절
		JsonNode draftRelease = createRelease(author, createQuestionnaire(author, "RQ-CH-DRAFT")
			.get("artifactId").asString());
		switchChannel(author, draftRelease.get("releaseId").asString(), previous)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RELEASE_NOT_PUBLISHED"));
		switchChannel(author, releaseA, releaseB).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CHANNEL_CHANGED"));
		// releaseId 칸을 빠뜨리면 파일 버전으로 되돌리지 않고 400(되돌리기는 명시적 null 만)
		perform(put(CHANNEL), author, toJson(Map.of("expectedPreviousReleaseId", releaseA, "reason", "누락")))
			.andExpect(status().isBadRequest());

		switchChannel(author, releaseA, previous).andExpect(status().isOk())
			.andExpect(jsonPath("$.surveyVersion").value("RQ-CH-A"));
		assertThat(currentVersion(member)).isEqualTo("RQ-CH-A");
		long sessionA = startSession(member, "RQ-CH-A");

		switchChannel(author, releaseB, releaseA).andExpect(status().isOk());
		assertThat(currentVersion(member)).isEqualTo("RQ-CH-B");
		// 이전 조합으로 시작한 회차는 그 버전으로 계속 답한다
		perform(get("/api/survey-sessions/" + sessionA), member, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.manifest.surveyVersion").value("RQ-CH-A"));
		perform(put("/api/survey-sessions/" + sessionA + "/answers/Q001"), member, "{\"value\":2}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.answeredCount").value(1));
		perform(get("/api/survey-sessions/" + fileSession), member, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.questionCount").value(100));

		// 되돌리기: 이전 게시 조합으로, 그리고 서버 파일 버전(null)으로
		switchChannel(author, releaseA, releaseB).andExpect(status().isOk());
		assertThat(currentVersion(member)).isEqualTo("RQ-CH-A");
		perform(get("/api/survey-sessions/" + sessionA), member, null).andExpect(status().isOk());
		switchChannel(author, null, releaseA).andExpect(status().isOk())
			.andExpect(jsonPath("$.releaseId").value(nullValue()))
			.andExpect(jsonPath("$.surveyVersion").value(FILE_VERSION));
		assertThat(currentVersion(member)).isEqualTo(FILE_VERSION);
		assertThat(jdbc.queryForObject("select count(*) from rule_audit where action='CHANNEL_SWITCH' "
			+ "and actor_id=?", Integer.class, userIdOf(author))).isEqualTo(4);
		if (previous != null) {
			switchChannel(author, previous, null).andExpect(status().isOk());
		}
	}

	@Test
	@DisplayName("이관 규칙은 이 조합의 설문으로 옮기는 규칙이고 실제 문항을 가리켜야 검증을 통과한다")
	void migrationReferences() throws Exception {
		JsonNode op = operator();
		String questionnaireId = createQuestionnaire(op, "RQ-MIG").get("artifactId").asString();
		String good = createMigration(op, "MIG-OK", List.of("Q001"), List.of("Q002"));
		String bad = createMigration(op, "MIG-BAD", List.of("Q999"), List.of());
		// 같은 문항을 옮기면서 다시 답하게 할 수는 없다
		Map<String, Object> overlap = new HashMap<>();
		overlap.put("kind", "MIGRATION");
		overlap.put("version", "MIG-OVERLAP");
		overlap.put("schemaVersion", "migration-1");
		overlap.put("document", Map.of("fromSurveyVersion", FILE_VERSION, "toSurveyVersion", "RQ-MIG",
			"preserve", List.of("Q001"), "reconfirm", List.of("Q001")));
		overlap.put("changeReason", "겹침");
		perform(post(ARTIFACTS), op, toJson(overlap)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields", hasItem("document.reconfirm")));

		JsonNode failed = validate(op, createRelease(op, questionnaireId, bad).get("releaseId").asString());
		assertThat(failed.get("status").asString()).isEqualTo("FAILED");
		assertThat(failed.toString()).contains("MIGRATION_REFERENCE");

		String releaseId = createRelease(op, questionnaireId, good).get("releaseId").asString();
		JsonNode validation = validate(op, releaseId);
		assertThat(validation.get("status").asString()).isEqualTo("PASSED");
		approve(operator(), releaseId, validation).andExpect(status().isCreated());
		publish(op, releaseId, validation).andExpect(status().isOk());

		// 게시한 이관 규칙은 파일 규칙처럼 회원의 이관 미리보기에 쓰인다.
		JsonNode member = registeredMember();
		long session = startSession(member, FILE_VERSION);
		perform(put("/api/survey-sessions/" + session + "/answers/Q001"), member, "{\"value\":3}")
			.andExpect(status().isOk());
		perform(get("/api/survey-sessions/" + session + "/migration-plan?targetSurveyVersion=RQ-MIG"), member, null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.preservedQuestionIds[0]").value("Q001"))
			.andExpect(jsonPath("$.reconfirmQuestionIds[0]").value("Q002"));
	}

	@Test
	@DisplayName("실제 100문항 설문 파일도 문항 수를 코드에 두지 않고 형식·회귀 사례 검증을 통과한다")
	void realQuestionnaireValidates() throws Exception {
		JsonNode op = operator();
		ObjectNode real = (ObjectNode) jsonMapper.readTree(new ClassPathResource(
			"survey/questionnaires/SIS-Q-1.1-100.json").getContentAsString(StandardCharsets.UTF_8));
		real.put("surveyVersion", "RQ-REAL");
		String id = json(perform(post(ARTIFACTS), op, toJson(input("RQ-REAL", real))).andExpect(status().isCreated()))
			.get("artifactId").asString();
		JsonNode validation = validate(op, createRelease(op, id).get("releaseId").asString());
		assertThat(validation.get("status").asString()).as(validation.toString()).isEqualTo("PASSED");
	}

	@Test
	@DisplayName("키링 매칭 규칙·제품 정책만 묶은 조합은 검증·게시되고 만남 승격이 그 조합을 찾지만, 설문 회차 전환에는 못 쓴다")
	void keyringRuleRelease() throws Exception {
		JsonNode author = operator();
		JsonNode reviewer = operator();
		String matching = createKeyringArtifact(author, "MATCHING", "r2-c-20261106", "matching-1", Map.of(
			"ruleVersion", "r2-c-20261106", "firmwareRuleId", 268439809L,
			"hardFilters", List.of("Q90", "Q91", "Q93", "Q95", "age"),
			"traitBands", Map.of("low", List.of(0, 0, 30, 60), "high", List.of(0, 40, 70, 100), "tolerancePerStep", 10),
			"priorityDomainWeight", 2, "minDirectionScore", 50, "minCoverage", 0.7, "combine", "harmonic"));
		String policy = createKeyringArtifact(author, "PRODUCT_POLICY", "tingle-policy-v2", "product-policy-1",
			Map.of("policyVersion", "tingle-policy-v2", "firmwarePolicyId", 1380991793L,
				"opportunitySeconds", 604800, "counterpartWaitSeconds", 604800, "proposalResponseSeconds", 86400,
				"leaseSeconds", 86400, "presenceGapSeconds", 60, "declineCooldownDays", 30));
		// 매칭 규칙 하나만으로는 조합을 만들 수 없다.
		perform(post(RELEASES), author, toJson(Map.of("name", "조합", "artifactIds", List.of(matching),
			"engineVersion", RuleDocuments.ENGINE_VERSION, "changeReason", "새 조합")))
			.andExpect(status().isBadRequest());

		String releaseId = createRelease(author, matching, policy).get("releaseId").asString();
		JsonNode validation = validate(author, releaseId);
		assertThat(validation.get("status").asString()).as(validation.toString()).isEqualTo("PASSED");
		approve(reviewer, releaseId, validation).andExpect(status().isCreated());
		publish(author, releaseId, validation).andExpect(status().isOk());
		switchChannel(author, releaseId, null).andExpect(status().isBadRequest());

		var resolver = new com.e106.tingle.encounter.peer.Ter2BusinessReleaseResolver(jdbc, jsonMapper);
		org.springframework.test.util.ReflectionTestUtils.setField(resolver, "configuredRelease", releaseId);
		org.springframework.test.util.ReflectionTestUtils.setField(resolver, "configuredHash",
			validation.get("candidateHash").asString());
		assertThat(resolver.resolve("tingle-policy-v2", "r2-c-20261106", java.time.Instant.now()))
			.hasToString(releaseId);
	}

	// ---------- 도우미 ----------

	private String createKeyringArtifact(JsonNode op, String kind, String version, String schema,
		Map<String, Object> document) throws Exception {
		return json(perform(post(ARTIFACTS), op, toJson(Map.of("kind", kind, "version", version,
			"schemaVersion", schema, "document", document, "changeReason", "키링 규칙"))).andExpect(status().isCreated()))
			.get("artifactId").asString();
	}

	private JsonNode operator() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		jdbc.update("update users set is_operator=true where id=?", userIdOf(tokens));
		return tokens;
	}

	private JsonNode registeredMember() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
		completeProfile(tokens).andExpect(status().isOk());
		return tokens;
	}

	private ObjectNode questionnaire(String version) {
		ObjectNode root = jsonMapper.createObjectNode().put("surveyVersion", version).put("wordingVersion", "W-1")
			.put("questionCount", 2);
		for (int i = 1; i <= 2; i++) {
			ObjectNode question = root.withArray("questions").addObject().put("questionId", "Q00" + i)
				.put("ordinal", i).put("part", 1).put("text", "문항 " + i).put("allowUnknown", true)
				.put("allowAny", false).put("calculationBinding", "E");
			ObjectNode schema = question.putObject("responseSchema");
			schema.putArray("oneOf").addObject().putArray("enum").add(1).add(2).add(3);
			schema.withArray("oneOf").addObject().put("const", "unknown");
			for (int value = 1; value <= 3; value++) {
				question.withArray("options").addObject().put("value", value).put("label", "보기 " + value);
			}
		}
		return root;
	}

	private Map<String, Object> input(String version, JsonNode document) {
		Map<String, Object> input = new HashMap<>();
		input.put("kind", "QUESTIONNAIRE");
		input.put("version", version);
		input.put("schemaVersion", "questionnaire-1");
		input.put("document", document);
		input.put("changeReason", "새 설문");
		return input;
	}

	private JsonNode createQuestionnaire(JsonNode op, String version) throws Exception {
		return json(perform(post(ARTIFACTS), op, toJson(input(version, questionnaire(version))))
			.andExpect(status().isCreated()));
	}

	private String createMigration(JsonNode op, String version, List<String> preserve, List<String> reconfirm)
		throws Exception {
		Map<String, Object> input = new HashMap<>();
		input.put("kind", "MIGRATION");
		input.put("version", version);
		input.put("schemaVersion", "migration-1");
		input.put("document", Map.of("fromSurveyVersion", FILE_VERSION, "toSurveyVersion", "RQ-MIG",
			"preserve", preserve, "reconfirm", reconfirm));
		input.put("changeReason", "이관 규칙");
		return json(perform(post(ARTIFACTS), op, toJson(input)).andExpect(status().isCreated()))
			.get("artifactId").asString();
	}

	private JsonNode createRelease(JsonNode op, String... artifactIds) throws Exception {
		return json(perform(post(RELEASES), op, toJson(Map.of("name", "조합", "artifactIds", List.of(artifactIds),
			"engineVersion", RuleDocuments.ENGINE_VERSION, "changeReason", "새 조합")))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.artifacts", hasSize(artifactIds.length))));
	}

	private JsonNode validate(JsonNode op, String releaseId) throws Exception {
		return json(perform(post(RELEASES + "/" + releaseId + "/validate"), op, null).andExpect(status().isOk()));
	}

	private ResultActions approve(JsonNode op, String releaseId, JsonNode validation) throws Exception {
		return perform(post(RELEASES + "/" + releaseId + "/approvals"), op, confirmation(validation));
	}

	private ResultActions publish(JsonNode op, String releaseId, JsonNode validation) throws Exception {
		return perform(post(RELEASES + "/" + releaseId + "/publish"), op, confirmation(validation));
	}

	private String confirmation(JsonNode validation) throws Exception {
		return toJson(Map.of("validationRunId", validation.get("validationRunId").asString(),
			"validationHash", validation.get("candidateHash").asString()));
	}

	private String publishedRelease(JsonNode author, JsonNode reviewer, String version) throws Exception {
		String releaseId = createRelease(author, createQuestionnaire(author, version).get("artifactId").asString())
			.get("releaseId").asString();
		JsonNode validation = validate(author, releaseId);
		approve(reviewer, releaseId, validation).andExpect(status().isCreated());
		publish(author, releaseId, validation).andExpect(status().isOk());
		return releaseId;
	}

	private ResultActions switchChannel(JsonNode op, String releaseId, String expectedPrevious) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("releaseId", releaseId);
		body.put("expectedPreviousReleaseId", expectedPrevious);
		body.put("reason", "전환");
		return perform(put(CHANNEL), op, toJson(body));
	}

	private String currentVersion(JsonNode member) throws Exception {
		return json(perform(get("/api/surveys/current"), member, null).andExpect(status().isOk()))
			.get("manifest").get("surveyVersion").asString();
	}

	private long startSession(JsonNode member, String version) throws Exception {
		return json(perform(post("/api/survey-sessions"), member, toJson(Map.of("surveyVersion", version)))
			.andExpect(status().isCreated())).get("sessionId").asLong();
	}

	private JsonNode json(ResultActions result) throws Exception {
		return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}
}
