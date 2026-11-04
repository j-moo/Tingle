package com.e106.tingle.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.chat.ChatRoomRepository;
import com.e106.tingle.icebreaker.IcebreakerGateway;
import com.e106.tingle.icebreaker.IcebreakerProcessor;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.ProposalService;
import com.e106.tingle.proposal.explanation.RuleExplanationProvider;
import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;

import tools.jackson.databind.JsonNode;

/**
 * 본인 비동기 작업 조회(GET /api/jobs/{jobId}). 상태 줄은 각 도메인 표에 직접 넣거나 실제 요청 API로 만든다.
 * 설명 제공자·시작 질문 AI 는 가짜로 바꾼다.
 */
@SpringBootTest(properties = {"app.icebreaker.scheduling-enabled=false",
	"app.match-explanations.scheduling-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class JobIntegrationTest extends IntegrationTestSupport {

	@Autowired
	private JobService jobs;
	@Autowired
	private ProposalService proposalService;
	@Autowired
	private ProposalRepository proposals;
	@Autowired
	private MatchingParticipantRepository participants;
	@Autowired
	private ChatRoomRepository rooms;
	@Autowired
	private IcebreakerProcessor icebreakerProcessor;
	@MockitoBean
	private RuleExplanationProvider explanationProvider;
	@MockitoBean
	private IcebreakerGateway icebreakerGateway;

	private JsonNode owner;
	private JsonNode other;
	private long ownerId;
	private long otherId;

	@BeforeEach
	void prepareMembers() throws Exception {
		given(icebreakerGateway.enabled()).willReturn(false);
		owner = login("kakao", newSocialId());
		other = login("kakao", newSocialId());
		completeOnboarding(owner);
		completeOnboarding(other);
		ownerId = userIdOf(owner);
		otherId = userIdOf(other);
	}

	@Test
	@DisplayName("분석 작업은 접수·실행 중·성공·정보 부족·실패를 구분하고 재시도 정보·내부 오류는 내보내지 않는다")
	void analysisStatuses() throws Exception {
		String future = Instant.now().plusSeconds(600).toString();
		String past = Instant.now().minusSeconds(600).toString();

		long accepted = analysis("PENDING", "{\"attempts\":0}");
		job(owner, "ANALYSIS-" + accepted).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.jobId").value("ANALYSIS-" + accepted))
			.andExpect(jsonPath("$.kind").value("ANALYSIS"))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.resourceId").value(accepted))
			.andExpect(jsonPath("$.errorCode").isEmpty())
			.andExpect(jsonPath("$.retryable").value(false))
			.andExpect(jsonPath("$.pollAfterSeconds").value(1));

		long running = analysis("PENDING", "{\"attempts\":1,\"nextRetryAt\":\"" + future + "\"}");
		job(owner, "ANALYSIS-" + running).andExpect(jsonPath("$.status").value("RUNNING"));
		// 선점 기한이 지나 멈춤 복구를 기다리는 줄은 다시 접수 상태다.
		long stuck = analysis("PENDING", "{\"attempts\":1,\"nextRetryAt\":\"" + past + "\"}");
		job(owner, "ANALYSIS-" + stuck).andExpect(jsonPath("$.status").value("PENDING"));
		// 자동 재시도를 기다리는 실패는 아직 끝나지 않았다.
		long retrying = analysis("FAILED",
			"{\"attempts\":1,\"lastErrorCode\":\"AI_CALL_ERROR\",\"nextRetryAt\":\"" + future + "\"}");
		job(owner, "ANALYSIS-" + retrying).andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.errorCode").isEmpty());

		long succeeded = analysis("SUCCEEDED", "{\"scores\":{\"I\":75.0},\"primaryType\":\"EMBER\"}");
		job(owner, "ANALYSIS-" + succeeded).andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.pollAfterSeconds").value(0));
		long insufficient = analysis("INSUFFICIENT", "{\"missingQuestionIds\":[\"Q001\"]}");
		job(owner, "ANALYSIS-" + insufficient).andExpect(jsonPath("$.status").value("INSUFFICIENT"))
			.andExpect(jsonPath("$.pollAfterSeconds").value(0));

		long failed = analysis("FAILED", "{\"attempts\":3,\"lastErrorCode\":\"AI_INVALID_RESPONSE\"}");
		JsonNode body = json(job(owner, "ANALYSIS-" + failed).andExpect(status().isOk()));
		assertThat(body.size()).isEqualTo(7);
		assertThat(body.path("status").asString()).isEqualTo("FAILED");
		assertThat(body.path("errorCode").asString()).isEqualTo("SERVICE_UNAVAILABLE");
		assertThat(body.path("retryable").asBoolean()).isTrue();
		assertThat(body.path("pollAfterSeconds").asInt()).isZero();
		assertThat(body.toString()).doesNotContain("AI_INVALID_RESPONSE", "attempts", "nextRetryAt");
	}

	@Test
	@DisplayName("남의 작업·없는 작업·형식이 틀린 번호는 모두 같은 404")
	void othersAndMissingAreTheSame404() throws Exception {
		long mine = analysis("PENDING", "{\"attempts\":0}");
		job(other, "ANALYSIS-" + mine).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		// 본인 결과라도 다른 표기·없는 종류·형식 오류는 없는 작업과 같다.
		for (String jobId : List.of("ANALYSIS-" + Long.MAX_VALUE, "ANALYSIS-0", "ANALYSIS-0" + mine,
			"ANALYSIS-+" + mine, "UNKNOWN-" + mine, "RISK_DETECTION-" + mine, "analysis-" + mine, "ANALYSIS",
			"12")) {
			job(owner, jobId).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		}
		mockMvc.perform(get("/api/jobs/ANALYSIS-" + mine)).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("설명 작업은 열람자 본인의 실행만 보이고, 실행 중·성공을 구분한다")
	void explanationJobs() throws Exception {
		long proposalId = proposal();
		String jobId = "MATCH_EXPLANATION-" + proposalId;
		// 요청 전에는 작업이 없다.
		job(owner, jobId).andExpect(status().isNotFound());

		AtomicReference<String> during = new AtomicReference<>();
		given(explanationProvider.explain(any())).willAnswer(invocation -> {
			during.set(jobs.get(ownerId, jobId).status());
			return new ExplanationStatus("SUCCEEDED", "RULE_FALLBACK", "synthetic", List.of("synthetic.rule"),
				null);
		});
		perform(post("/api/match-proposals/" + proposalId + "/explanations")
			.header("Idempotency-Key", UUID.randomUUID()), owner, null).andExpect(status().isAccepted());
		assertThat(during.get()).isEqualTo("RUNNING");

		JsonNode body = json(job(owner, jobId).andExpect(status().isOk()));
		assertThat(body.size()).isEqualTo(7);
		assertThat(body.path("kind").asString()).isEqualTo("MATCH_EXPLANATION");
		assertThat(body.path("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(body.path("resourceId").asLong()).isEqualTo(proposalId);
		assertThat(body.toString()).doesNotContain("synthetic", "attemptId", "inputFingerprint");
		// 같은 제안의 상대는 자기 실행이 없으니 404, 제안 밖 회원도 404.
		job(other, jobId).andExpect(status().isNotFound());
		JsonNode outsider = login("kakao", newSocialId());
		completeOnboarding(outsider);
		job(outsider, jobId).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("시작 질문 작업은 방 참여자만 보고, 접수·실행 중·성공을 구분한다")
	void icebreakerJobs() throws Exception {
		long roomId = room();
		String jobId = "ICEBREAKERS-" + roomId;
		job(owner, jobId).andExpect(status().isNotFound());

		perform(post("/api/chat-rooms/" + roomId + "/icebreakers").header("Idempotency-Key", UUID.randomUUID()),
			owner, null).andExpect(status().isAccepted());
		job(owner, jobId).andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("ICEBREAKERS"))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.resourceId").value(roomId));
		// 방의 결과는 두 참여자가 함께 보므로 상대도 같은 작업을 본다.
		job(other, jobId).andExpect(jsonPath("$.status").value("PENDING"));

		jdbc.update("update chat set icebreakers=jsonb_set(icebreakers,'{leaseUntil}',to_jsonb(cast(? as text))) "
			+ "where id=?", Instant.now().plusSeconds(30).toString(), roomId);
		job(owner, jobId).andExpect(jsonPath("$.status").value("RUNNING"));
		jdbc.update("update chat set icebreakers=icebreakers-'leaseUntil' where id=?", roomId);

		icebreakerProcessor.process(roomId);
		JsonNode body = json(job(owner, jobId).andExpect(status().isOk()));
		assertThat(body.size()).isEqualTo(7);
		assertThat(body.path("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(body.toString()).doesNotContain("questions", "generation", "receipts", "requestedBy");

		JsonNode outsider = login("kakao", newSocialId());
		completeOnboarding(outsider);
		job(outsider, jobId).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}

	// 본인 완료 회차에 주어진 상태의 분석 줄을 만든다.
	private long analysis(String status, String result) {
		Long surveyId = jdbc.queryForObject("insert into survey(user_id,survey_version,revision,completed_at) "
			+ "values (?,'SIS-Q-1.1-100',0,now()) returning id", Long.class, ownerId);
		return jdbc.queryForObject("insert into personality(survey_id,analysis_version,status,result) "
			+ "values (?,'job-test',cast(? as analysis_status),cast(? as jsonb)) returning id", Long.class,
			surveyId, status, result);
	}

	private long proposal() {
		MatchProposal proposal = proposals.saveAndFlush(MatchProposal.proposed(ownerId, otherId, clock.instant()));
		participants.saveAllAndFlush(List.of(new MatchingParticipant(proposal.getId(), ownerId),
			new MatchingParticipant(proposal.getId(), otherId)));
		jdbc.update("update matching set evaluation=cast(? as jsonb) where id=?",
			"{\"ruleVersion\":\"synthetic-v1\",\"decision\":\"MATCHED\"}", proposal.getId());
		return proposal.getId();
	}

	private long room() {
		long proposalId = proposal();
		proposalService.decide(proposalId, ownerId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposalService.profile(proposalId, ownerId).profileVersion());
		proposalService.decide(proposalId, otherId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposalService.profile(proposalId, otherId).profileVersion());
		return rooms.findBySourceProposalId(proposalId).orElseThrow().getId();
	}

	private ResultActions job(JsonNode tokens, String jobId) throws Exception {
		return perform(get("/api/jobs/" + jobId), tokens, null);
	}

	private JsonNode json(ResultActions result) throws Exception {
		return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}
}
