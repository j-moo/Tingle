package com.e106.tingle.icebreaker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.chat.ChatRoom;
import com.e106.tingle.chat.ChatRoomRepository;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.ProposalService;

import tools.jackson.databind.JsonNode;

/** 실제 PostgreSQL·보안 API 검증. 외부 AI만 대체하며 실제 AI 개발서버 검증과 구분한다. */
@SpringBootTest(properties = "app.icebreaker.scheduling-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class IcebreakerTopicIntegrationTest extends IntegrationTestSupport {

	private static final String CONSENT_VERSION = "q040-consent-v1";
	@Autowired
	private ProposalService proposals;
	@Autowired
	private ProposalRepository proposalRows;
	@Autowired
	private MatchingParticipantRepository participants;
	@Autowired
	private ChatRoomRepository rooms;
	@Autowired
	private IcebreakerProcessor processor;
	@Autowired
	private IcebreakerStore store;
	@Autowired
	private IcebreakerQuestions questions;
	@Autowired
	private IcebreakerTopicConsentService consents;
	@MockitoBean
	private IcebreakerGateway gateway;
	private JsonNode first;
	private JsonNode second;
	private long firstId;
	private long secondId;
	private ChatRoom room;

	@BeforeEach
	void prepareRoom() throws Exception {
		first = readyUser();
		second = readyUser();
		firstId = userIdOf(first);
		secondId = userIdOf(second);
		room = createRoom();
		answer(firstId, "SIS-Q-1.1-100", "ARRAY", "[\"talk\",\"rest\"]");
		answer(secondId, "SIS-Q-1.1-100", "ARRAY", "[\"rest\",\"talk\"]");
		given(gateway.enabled()).willReturn(true);
		given(gateway.manifest()).willReturn(Map.of("releaseId", UUID.randomUUID().toString()));
		given(gateway.generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class)))
			.willReturn(questions.fallback());
		given(gateway.generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class), anyString()))
			.willReturn(commonResult());
	}

	@Test
	void selectsTheFirstSharedCodeAndStoresOnlyMinimalProvenance() throws Exception {
		grantBoth();
		request(UUID.randomUUID());
		processor.process(room.getId());
		then(gateway).should().generate(eq(room.getId()), any(UUID.class), anyMap(), any(Instant.class), eq("rest"));
		JsonNode result = result();
		assertThat(result.path("source").asString()).isEqualTo("COMMON_TOPIC_TEMPLATE");
		assertThat(result.path("promptVersion").asString()).isEqualTo("q040-template-v1");
		assertThat(result.path("questions").size()).isEqualTo(3);
		assertThat(result.size()).isEqualTo(4);
		JsonNode saved = state();
		assertThat(saved.path("commonTopicKey").asString()).isEqualTo("rest");
		assertThat(saved.path("topicSelection").path("participants").size()).isEqualTo(2);
		assertThat(saved.toString()).doesNotContain("answerValue", "answer_type", "[\"talk\",\"rest\"]");
	}

	@Test
	void missingOnePurposeConsentSendsNoTopicAndReturnsThreeGeneralQuestions() throws Exception {
		consents.update(firstId, true, CONSENT_VERSION);
		request(UUID.randomUUID());
		processor.process(room.getId());
		assertGeneral();
		then(gateway).should(never()).generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class), anyString());
		assertThat(state().has("commonTopicKey")).isFalse();
	}

	@Test
	void unknownSurveyVersionCannotReuseEarlierSupportedAnswers() throws Exception {
		grantBoth();
		answer(secondId, "unresolved-version", "ARRAY", "[\"rest\"]");
		request(UUID.randomUUID());
		processor.process(room.getId());
		assertGeneral();
		then(gateway).should(never()).generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class), anyString());
	}

	@Test
	void disjointAnswersProduceGeneralQuestions() throws Exception {
		grantBoth();
		answer(secondId, "SIS-Q-1.1-100", "ARRAY", "[\"active\"]");
		request(UUID.randomUUID());
		processor.process(room.getId());
		assertGeneral();
	}

	@Test
	void unknownAnswerDoesNotFallBackToAnOlderSharedAnswer() throws Exception {
		grantBoth();
		answer(secondId, "SIS-Q-1.1-100", "STRING", "unknown");
		request(UUID.randomUUID());
		processor.process(room.getId());
		assertGeneral();
	}

	@Test
	void anUnrequestedCommonResponseIsRejectedBeforePersistence() throws Exception {
		given(gateway.generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class)))
			.willReturn(commonResult());
		request(UUID.randomUUID());
		processor.process(room.getId());
		assertGeneral();
		assertThat(state().toString()).doesNotContain("공통 휴식 질문");
	}

	@Test
	void requestedTopicMayReturnTheGeneralFallback() throws Exception {
		grantBoth();
		given(gateway.generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class), anyString()))
			.willReturn(questions.fallback());
		request(UUID.randomUUID());
		processor.process(room.getId());
		assertGeneral();
	}

	@Test
	void withdrawalErasesStoredCommonQuestionsBeforeAnyFurtherRead() throws Exception {
		grantBoth();
		request(UUID.randomUUID());
		processor.process(room.getId());
		assertThat(result().path("source").asString()).isEqualTo("COMMON_TOPIC_TEMPLATE");
		consents.update(secondId, false, CONSENT_VERSION);
		JsonNode immediatelyStored = state();
		assertThat(immediatelyStored.path("source").asString()).isEqualTo("GENERAL_FALLBACK");
		assertThat(immediatelyStored.has("topicSelection")).isFalse();
		assertThat(immediatelyStored.has("commonTopicKey")).isFalse();
		assertThat(immediatelyStored.toString()).doesNotContain("공통 휴식 질문");
		assertThat(jdbc.queryForObject("select closed_at is null from chat where id=?", Boolean.class, room.getId()))
			.isTrue();
		assertGeneral();
	}

	@Test
	void lookupRechecksCurrentConsentEvenWhenEarlierCleanupDidNotRun() throws Exception {
		grantBoth();
		request(UUID.randomUUID());
		processor.process(room.getId());
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (?,'ICEBREAKER_TOPIC','unknown-version',true,?)", firstId, Timestamp.from(clock.instant()));
		assertGeneral();
		assertThat(state().has("topicSelection")).isFalse();
		assertThat(state().toString()).doesNotContain("공통 휴식 질문");
	}

	@Test
	void reconsentRequiresANewGenerationAndCannotResurrectAnOlderClaim() throws Exception {
		grantBoth();
		UUID oldKey = UUID.randomUUID();
		JsonNode accepted = request(oldKey);
		IcebreakerStore.Claim oldClaim = store.claim(room.getId());
		assertThat(oldClaim.commonTopicKey()).isEqualTo("rest");
		consents.update(firstId, false, CONSENT_VERSION);
		consents.update(firstId, true, CONSENT_VERSION);
		assertGeneral();
		assertThat(request(oldKey)).isEqualTo(accepted);
		request(UUID.randomUUID());
		IcebreakerStore.Claim newClaim = store.claim(room.getId());
		assertThat(newClaim.generation()).isNotEqualTo(oldClaim.generation());
		assertThat(newClaim.topicSelection()).isNotEqualTo(oldClaim.topicSelection());
		assertThat(store.complete(oldClaim, commonResult(), false)).isFalse();
		assertThat(state().path("status").asString()).isEqualTo("PENDING");
		assertThat(store.complete(newClaim, commonResult(), false)).isTrue();
		assertThat(result().path("source").asString()).isEqualTo("COMMON_TOPIC_TEMPLATE");
	}

	@Test
	void delayedProviderResponseCannotRestoreQuestionsAfterWithdrawalAndReconsent() throws Exception {
		grantBoth();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		given(gateway.generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class), anyString()))
			.willAnswer(invocation -> {
				entered.countDown();
				if (!release.await(10, TimeUnit.SECONDS)) {
					throw new AssertionError("Provider release timed out");
				}
				return commonResult();
			});
		request(UUID.randomUUID());
		try (var executor = Executors.newSingleThreadExecutor()) {
			var completion = executor.submit(() -> processor.process(room.getId()));
			try {
				assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
				consents.update(secondId, false, CONSENT_VERSION);
				assertGeneral();
				consents.update(secondId, true, CONSENT_VERSION);
				assertGeneral();
			} finally {
				release.countDown();
			}
			assertThat(completion.get(10, TimeUnit.SECONDS)).isFalse();
		}
		assertGeneral();
		assertThat(state().toString()).doesNotContain("공통 휴식 질문");
	}

	@Test
	void consentApiIsSelfOnlyVersionedAndIdempotent() throws Exception {
		String path = consentPath(firstId);
		perform(get(path), first, null).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.enabled").value(false))
			.andExpect(jsonPath("$.noticeVersion").value(CONSENT_VERSION));
		perform(put(path), second, consentBody(true, CONSENT_VERSION)).andExpect(status().isForbidden());
		perform(get(path), second, null).andExpect(status().isForbidden());
		perform(put(path), first, consentBody(true, "old-version")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("AGREEMENT_VERSION_OUTDATED"));
		perform(put(path), first, "{\"noticeVersion\":\"q040-consent-v1\"}")
			.andExpect(status().isBadRequest());
		for (int index = 0; index < 2; index++) {
			perform(put(path), first, consentBody(true, CONSENT_VERSION)).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.enabled").value(true));
		}
		assertThat(jdbc.queryForObject("select count(*) from user_consents "
			+ "where user_id=? and consent_type='ICEBREAKER_TOPIC'", Long.class, firstId)).isEqualTo(1L);
		perform(put(path), first, consentBody(false, CONSENT_VERSION)).andExpect(status().isOk())
			.andExpect(jsonPath("$.enabled").value(false));
		assertThat(jdbc.queryForObject("select count(*) from user_consents "
			+ "where user_id=? and consent_type='ICEBREAKER_TOPIC'", Long.class, firstId)).isEqualTo(2L);
	}

	private void grantBoth() {
		consents.update(firstId, true, CONSENT_VERSION);
		consents.update(secondId, true, CONSENT_VERSION);
	}

	private long answer(long userId, String version, String type, String value) {
		Long surveyId = jdbc.queryForObject("insert into survey(user_id,survey_version,completed_at,revision) "
			+ "values (?,?,?,0) returning id", Long.class, userId, version, Timestamp.from(clock.instant()));
		jdbc.update("insert into survey_answer(survey_id,question_id,answer_type,answer_value,answered_at) "
			+ "values (?,'Q040',?,?,?)", surveyId, type, value, Timestamp.from(clock.instant()));
		return surveyId;
	}

	private JsonNode readyUser() throws Exception {
		JsonNode tokens = login("google", newSocialId());
		completeOnboarding(tokens);
		return tokens;
	}

	private ChatRoom createRoom() {
		MatchProposal proposal = proposalRows.saveAndFlush(MatchProposal.proposed(firstId, secondId, clock.instant()));
		participants.saveAllAndFlush(List.of(new MatchingParticipant(proposal.getId(), firstId),
			new MatchingParticipant(proposal.getId(), secondId)));
		proposals.decide(proposal.getId(), firstId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposal.getId(), firstId).profileVersion());
		proposals.decide(proposal.getId(), secondId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposal.getId(), secondId).profileVersion());
		return rooms.findBySourceProposalId(proposal.getId()).orElseThrow();
	}

	private IcebreakerGateway.Generated commonResult() {
		return new IcebreakerGateway.Generated(List.of("공통 휴식 질문", "두 번째 활동 질문", "세 번째 대화 질문"),
			"COMMON_TOPIC_TEMPLATE", "q040-template-v1");
	}

	private JsonNode request(UUID key) throws Exception {
		String response = perform(post(roomPath()).header("Idempotency-Key", key), first, null)
			.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return jsonMapper.readTree(response);
	}

	private JsonNode result() throws Exception {
		String response = perform(get(roomPath()), first, null).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andReturn().getResponse().getContentAsString();
		return jsonMapper.readTree(response);
	}

	private void assertGeneral() throws Exception {
		JsonNode result = result();
		assertThat(result.path("source").asString()).isEqualTo("GENERAL_FALLBACK");
		assertThat(result.path("questions").size()).isEqualTo(3);
		assertThat(result.path("questions")).isEqualTo(jsonMapper.valueToTree(questions.fallback().questions()));
	}

	private JsonNode state() {
		return jsonMapper.readTree(jdbc.queryForObject("select icebreakers::text from chat where id=?",
			String.class, room.getId()));
	}

	private String roomPath() {
		return "/api/chat-rooms/" + room.getId() + "/icebreakers";
	}

	private String consentPath(long userId) {
		return "/api/users/" + userId + "/icebreaker-topic-consent";
	}

	private String consentBody(boolean enabled, String version) {
		return jsonMapper.writeValueAsString(Map.of("enabled", enabled, "noticeVersion", version));
	}
}
