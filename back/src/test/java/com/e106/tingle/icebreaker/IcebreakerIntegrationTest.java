package com.e106.tingle.icebreaker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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

/**
 * 실제 HTTP 보안·JPA·PostgreSQL을 사용한다. 소셜·시계·AI gateway만 대체하며 실제 AI/기기 검증이 아니다.
 */
@SpringBootTest(properties = {"app.icebreaker.scheduling-enabled=false",
	"app.icebreaker.max-receipts-per-member=4"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class IcebreakerIntegrationTest extends IntegrationTestSupport {

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
	private Clock testClock;
	@MockitoBean
	private IcebreakerGateway gateway;
	private JsonNode first;
	private JsonNode second;
	private long firstId;
	private long secondId;
	private ChatRoom room;

	@BeforeEach
	void prepareRoom() throws Exception {
		given(gateway.enabled()).willReturn(false);
		first = newReadyUser();
		second = newReadyUser();
		firstId = userIdOf(first);
		secondId = userIdOf(second);
		room = createRoom(firstId, secondId);
	}

	@Test
	void unrequestedRoomDoesNotPretendThatGenerationHasStarted() throws Exception {
		perform(get(path(room)), first, null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		assertThat(storedState(room)).isEqualTo("{}");
	}

	@Test
	void acceptedRequestHasTheExactTaskContractAndFallbackHasTheExactStatusContract() throws Exception {
		JsonNode accepted = request(room, first, UUID.randomUUID());
		assertThat(accepted.size()).isEqualTo(7);
		assertThat(accepted.path("chatRoomId").asLong()).isEqualTo(room.getId());
		assertThat(accepted.path("kind").asString()).isEqualTo("ICEBREAKERS");
		assertThat(accepted.path("status").asString()).isEqualTo("PENDING");
		assertThat(accepted.path("statusUrl").asString()).isEqualTo(path(room));
		assertThat(accepted.path("errorCode").isNull()).isTrue();
		assertThat(accepted.path("retryable").asBoolean()).isFalse();
		assertThat(accepted.path("pollAfterSeconds").asLong()).isGreaterThanOrEqualTo(1);
		assertThat(accepted.has("jobId")).isFalse();
		perform(get(path(room)), second, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.questions").isEmpty())
			.andExpect(jsonPath("$.source").isEmpty())
			.andExpect(jsonPath("$.promptVersion").isEmpty());

		processor.process(room.getId());
		JsonNode result = result(room, first);
		assertThat(result.size()).isEqualTo(4);
		assertThat(result.path("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(result.path("source").asString()).isEqualTo("GENERAL_FALLBACK");
		assertThat(result.path("promptVersion").asString()).isNotBlank();
		assertThat(result.path("questions").size()).isEqualTo(3);
		for (JsonNode question : result.path("questions")) {
			String text = question.asString();
			assertThat(text.codePointCount(0, text.length())).isBetween(1, 200);
		}
		then(gateway).should(never()).generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class));
	}

	@Test
	void responseLossReplaysTheOriginalTaskWhileGetReturnsTheCurrentResult() throws Exception {
		UUID key = UUID.randomUUID();
		JsonNode original = request(room, first, key);
		processor.process(room.getId());
		assertThat(result(room, first).path("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(request(room, first, key)).isEqualTo(original);
		assertThat(request(room, second, UUID.randomUUID()).path("status").asString())
			.isEqualTo("SUCCEEDED");
		assertThat(result(room, first)).isEqualTo(result(room, second));
		then(gateway).should(never()).generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class));
	}

	@Test
	void concurrentParticipantsShareOneRoomResult() throws Exception {
		List<JsonNode> accepted = runConcurrently(
			() -> request(room, first, UUID.randomUUID()),
			() -> request(room, second, UUID.randomUUID()));
		assertThat(accepted).allSatisfy(task -> assertThat(task.path("chatRoomId").asLong())
			.isEqualTo(room.getId()));
		processor.process(room.getId());
		String completed = storedState(room);
		processor.process(room.getId());
		assertThat(storedState(room)).isEqualTo(completed);
		assertThat(result(room, first)).isEqualTo(result(room, second));
	}

	@Test
	void oneMembersKeyCannotBeReusedForAnotherRoom() throws Exception {
		JsonNode third = newReadyUser();
		ChatRoom other = createRoom(firstId, userIdOf(third));
		UUID key = UUID.randomUUID();
		request(room, first, key);
		perform(post(path(other)).header("Idempotency-Key", key), first, null)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
		assertThat(storedState(other)).isEqualTo("{}");
	}

	@Test
	void simultaneousSameKeyInDifferentRoomsIsResolvedAtomically() throws Exception {
		JsonNode third = newReadyUser();
		ChatRoom other = createRoom(firstId, userIdOf(third));
		UUID key = UUID.randomUUID();
		List<Integer> statuses = runConcurrently(
			() -> perform(post(path(room)).header("Idempotency-Key", key), first, null)
				.andReturn().getResponse().getStatus(),
			() -> perform(post(path(other)).header("Idempotency-Key", key), first, null)
				.andReturn().getResponse().getStatus());
		assertThat(statuses).containsExactlyInAnyOrder(202, 409);
		assertThat(List.of(storedState(room), storedState(other))).filteredOn("{}"::equals).hasSize(1);
	}

	@Test
	void malformedOrMissingKeysAndUnexpectedBodiesDoNotCreateWork() throws Exception {
		perform(post(path(room)), first, null).andExpect(status().isBadRequest());
		perform(post(path(room)).header("Idempotency-Key", "not-a-uuid"), first, null)
			.andExpect(status().isBadRequest());
		perform(post(path(room)).header("Idempotency-Key", UUID.randomUUID()), first,
			"{\"prompt\":\"unexpected private input\"}").andExpect(status().isBadRequest());
		assertThat(storedState(room)).isEqualTo("{}");
	}

	@Test
	void shortenedAndWhitespacePaddedKeysDoNotCreateWork() throws Exception {
		String canonical = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
		for (String invalid : List.of("1-1-1-1-1", " " + canonical, canonical + " ", " " + canonical + " ")) {
			perform(post(path(room)).header("Idempotency-Key", invalid), first, null)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
		assertThat(storedState(room)).isEqualTo("{}");
	}

	@Test
	void canonicalUppercaseAndLowercaseKeysRecoverTheSameResponse() throws Exception {
		UUID key = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
		JsonNode accepted = jsonMapper.readTree(perform(post(path(room))
			.header("Idempotency-Key", key.toString().toUpperCase(Locale.ROOT)), first, null)
			.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
		assertThat(request(room, first, key)).isEqualTo(accepted);
	}

	@Test
	void receiptLimitRejectsOnlyNewKeysAndPreservesExistingResponseRecovery() throws Exception {
		UUID originalKey = UUID.randomUUID();
		JsonNode original = request(room, first, originalKey);
		for (int index = 0; index < 3; index++) {
			request(room, first, UUID.randomUUID());
		}
		String before = storedState(room);
		perform(post(path(room)).header("Idempotency-Key", UUID.randomUUID()), first, null)
			.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		assertThat(storedState(room)).isEqualTo(before);
		assertThat(request(room, first, originalKey)).isEqualTo(original);
		assertThat(request(room, second, UUID.randomUUID()).path("status").asString()).isEqualTo("PENDING");
	}

	@Test
	void authenticationAndOwnershipCannotBeBypassed() throws Exception {
		mockMvc.perform(get(path(room))).andExpect(status().isUnauthorized());
		JsonNode outsider = newReadyUser();
		perform(get(path(room)), outsider, null).andExpect(status().isNotFound());
		perform(post(path(room)).header("Idempotency-Key", UUID.randomUUID()), outsider, null)
			.andExpect(status().isNotFound());
		assertThat(storedState(room)).isEqualTo("{}");
	}

	@Test
	void closedRoomRejectsBothNewRequestsAndOldReceiptReplays() throws Exception {
		UUID key = UUID.randomUUID();
		request(room, first, key);
		jdbc.update("update chat set closed_at=?,close_reason='LEFT' where id=?",
			Timestamp.from(testClock.instant()), room.getId());
		perform(get(path(room)), first, null).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("CHAT_CLOSED"));
		perform(post(path(room)).header("Idempotency-Key", key), first, null).andExpect(status().isGone());
		perform(post(path(room)).header("Idempotency-Key", UUID.randomUUID()), second, null)
			.andExpect(status().isGone());
		processor.process(room.getId());
		JsonNode cancelled = jsonMapper.readTree(storedState(room));
		assertThat(cancelled.path("status").asString()).isEqualTo("FAILED");
		assertThat(cancelled.path("discarded").asBoolean()).isTrue();
		then(gateway).should(never()).generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class));
	}

	@Test
	void expiryIsEnforcedAtTheBoundaryWithoutWaitingForTheClosingJob() throws Exception {
		Instant now = testClock.instant();
		jdbc.update("update chat set expires_at=? where id=?", Timestamp.from(now), room.getId());
		perform(post(path(room)).header("Idempotency-Key", UUID.randomUUID()), first, null)
			.andExpect(status().isGone());
		perform(get(path(room)), second, null).andExpect(status().isGone());
		assertThat(storedState(room)).isEqualTo("{}");
	}

	@Test
	void withdrawalAfterCreationMakesTheOldRoomUnavailableToBothParticipants() throws Exception {
		UUID key = UUID.randomUUID();
		request(room, first, key);
		withdraw(firstId, room.getCreatedAt().plusSeconds(1));
		perform(get(path(room)), first, null).andExpect(status().isGone());
		perform(get(path(room)), second, null).andExpect(status().isGone());
		perform(post(path(room)).header("Idempotency-Key", key), first, null).andExpect(status().isGone());
	}

	@Test
	void consentedPartnerInANewRoomCanReceiveGeneralQuestionsWithoutExternalTransmission() throws Exception {
		withdraw(firstId, testClock.instant());
		JsonNode third = newReadyUser();
		ChatRoom next = createRoom(firstId, userIdOf(third));
		perform(post(path(next)).header("Idempotency-Key", UUID.randomUUID()), first, null)
			.andExpect(status().isForbidden());
		request(next, third, UUID.randomUUID());
		processor.process(next.getId());
		assertThat(result(next, third).path("source").asString()).isEqualTo("GENERAL_FALLBACK");
		perform(get(path(next)), first, null).andExpect(status().isForbidden());
		then(gateway).should(never()).generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class));
	}

	@Test
	void partialJsonUpdatePreservesOtherChatStateAndLifetime() throws Exception {
		jdbc.update("update chat set ai_state=cast(? as jsonb),last_message_seq=41,retain_until=? where id=?",
			"{\"sentinel\":{\"attempt\":\"other-feature\"}}",
			Timestamp.from(testClock.instant().plus(Duration.ofDays(90))), room.getId());
		Map<String, Object> before = unaffectedColumns(room);
		request(room, first, UUID.randomUUID());
		processor.process(room.getId());
		assertThat(unaffectedColumns(room)).isEqualTo(before);
		assertThat(jdbc.queryForObject("select count(*) from chat_message where chat_id=?", Long.class,
			room.getId())).isZero();
	}

	@Test
	void transientAiFailuresUseOneFixedBudgetAndEventuallyFallback() throws Exception {
		enableSyntheticGateway();
		given(gateway.generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class)))
			.willThrow(new IcebreakerGateway.GatewayException(true));
		UUID key = UUID.randomUUID();
		JsonNode original = request(room, first, key);
		JsonNode initial = jsonMapper.readTree(storedState(room));
		Instant accepted = Instant.parse(initial.path("acceptedAt").asString());
		Instant deadline = Instant.parse(initial.path("deadlineAt").asString());
		assertThat(Duration.between(accepted, deadline)).isEqualTo(Duration.ofMinutes(5));
		for (int attempt = 1; attempt <= 3; attempt++) {
			processor.process(room.getId());
			JsonNode state = jsonMapper.readTree(storedState(room));
			assertThat(state.path("attempts").asInt()).isEqualTo(attempt);
			assertThat(state.path("acceptedAt")).isEqualTo(initial.path("acceptedAt"));
			assertThat(state.path("deadlineAt")).isEqualTo(initial.path("deadlineAt"));
			assertThat(state.path("generation")).isEqualTo(initial.path("generation"));
			assertThat(request(room, first, key)).isEqualTo(original);
			request(room, second, UUID.randomUUID());
			if (attempt < 3) {
				Instant nextRetry = Instant.parse(state.path("nextRetryAt").asString());
				given(testClock.instant()).willReturn(nextRetry.plusMillis(1));
			}
		}
		assertThat(result(room, first).path("source").asString()).isEqualTo("GENERAL_FALLBACK");
		then(gateway).should(times(3)).generate(anyLong(), any(UUID.class), anyMap(), eq(deadline));
	}

	@Test
	void persistedDeadlinePreventsARecoveredWorkerFromStartingAnotherAiAttempt() throws Exception {
		enableSyntheticGateway();
		given(gateway.generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class)))
			.willThrow(new IcebreakerGateway.GatewayException(true));
		request(room, first, UUID.randomUUID());
		processor.process(room.getId());
		JsonNode saved = jsonMapper.readTree(storedState(room));
		given(testClock.instant()).willReturn(Instant.parse(saved.path("deadlineAt").asString()));
		processor.process(room.getId());
		assertThat(result(room, first).path("source").asString()).isEqualTo("GENERAL_FALLBACK");
		assertThat(jsonMapper.readTree(storedState(room)).path("attempts").asInt()).isEqualTo(1);
		then(gateway).should(times(1)).generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class));
	}

	@Test
	void timedOutCallAtTheFixedDeadlineFallsBackAndTheNextRoomStillProgresses() throws Exception {
		enableSyntheticGateway();
		UUID key = UUID.randomUUID();
		JsonNode original = request(room, first, key);
		JsonNode initial = jsonMapper.readTree(storedState(room));
		Instant deadline = Instant.parse(initial.path("deadlineAt").asString());
		given(testClock.instant()).willReturn(deadline.minusSeconds(1));
		given(gateway.generate(eq(room.getId()), any(UUID.class), anyMap(), eq(deadline)))
			.willAnswer(invocation -> {
				given(testClock.instant()).willReturn(deadline);
				throw new IcebreakerGateway.GatewayException(true);
			});
		processor.process(room.getId());
		assertThat(result(room, first).path("source").asString()).isEqualTo("GENERAL_FALLBACK");
		JsonNode after = jsonMapper.readTree(storedState(room));
		assertThat(after.path("deadlineAt")).isEqualTo(initial.path("deadlineAt"));
		assertThat(after.path("acceptedAt")).isEqualTo(initial.path("acceptedAt"));
		assertThat(after.path("attempts").asInt()).isEqualTo(1);
		assertThat(request(room, first, key)).isEqualTo(original);
		given(gateway.enabled()).willReturn(false);
		JsonNode third = newReadyUser();
		ChatRoom next = createRoom(firstId, userIdOf(third));
		request(next, first, UUID.randomUUID());
		processor.runOnce();
		assertThat(result(next, first).path("source").asString()).isEqualTo("GENERAL_FALLBACK");
	}

	@Test
	void anAiResultAtTheOverallDeadlineCannotReplaceTheFallback() throws Exception {
		enableSyntheticGateway();
		request(room, first, UUID.randomUUID());
		Instant deadline = Instant.parse(jsonMapper.readTree(storedState(room)).path("deadlineAt").asString());
		given(testClock.instant()).willReturn(deadline.minusMillis(500));
		given(gateway.generate(anyLong(), any(UUID.class), anyMap(), eq(deadline))).willAnswer(invocation -> {
			given(testClock.instant()).willReturn(deadline);
			return syntheticResult();
		});
		processor.process(room.getId());
		assertThat(result(room, first).path("source").asString()).isEqualTo("GENERAL_FALLBACK");
		assertThat(jsonMapper.readTree(storedState(room)).path("attempts").asInt()).isEqualTo(1);
	}

	@Test
	void malformedAiOutputIsReplacedByTheCompleteGeneralQuestionSet() throws Exception {
		enableSyntheticGateway();
		given(gateway.generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class))).willReturn(
			new IcebreakerGateway.Generated(List.of("one", "two"), "GENERATED", "synthetic-v1"));
		request(room, first, UUID.randomUUID());
		processor.process(room.getId());
		JsonNode result = result(room, first);
		assertThat(result.path("source").asString()).isEqualTo("GENERAL_FALLBACK");
		assertThat(result.path("questions").size()).isEqualTo(3);
		assertThat(result.path("questions").toString()).doesNotContain("one", "two");
	}

	@Test
	void anAiResultArrivingAfterClosureCannotBeSavedOrDisclosed() throws Exception {
		enableSyntheticGateway();
		given(gateway.generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class))).willAnswer(invocation -> {
			jdbc.update("update chat set closed_at=?,close_reason='LEFT' where id=?",
				Timestamp.from(testClock.instant()), room.getId());
			return syntheticResult();
		});
		request(room, first, UUID.randomUUID());
		processor.process(room.getId());
		JsonNode stored = jsonMapper.readTree(storedState(room));
		assertThat(stored.path("questions").size()).isZero();
		assertThat(stored.path("source").asString()).isNotEqualTo("GENERATED");
		assertThat(stored.path("status").asString()).isEqualTo("FAILED");
		assertThat(stored.path("discarded").asBoolean()).isTrue();
		perform(get(path(room)), first, null).andExpect(status().isGone());
	}

	@Test
	void anAiResultAtTheLeaseDeadlineCannotCompleteTheExpiredClaim() throws Exception {
		enableSyntheticGateway();
		given(gateway.generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class))).willAnswer(invocation -> {
			JsonNode claimed = jsonMapper.readTree(storedState(room));
			given(testClock.instant()).willReturn(Instant.parse(claimed.path("leaseUntil").asString()));
			return syntheticResult();
		});
		request(room, first, UUID.randomUUID());
		processor.process(room.getId());
		JsonNode stored = jsonMapper.readTree(storedState(room));
		assertThat(stored.path("status").asString()).isEqualTo("PENDING");
		assertThat(stored.path("questions").size()).isZero();
		assertThat(stored.path("attempts").asInt()).isEqualTo(1);
		then(gateway).should(times(1)).generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class));
	}

	@Test
	void replacedExecutionGenerationRejectsTheOlderAiResult() throws Exception {
		enableSyntheticGateway();
		given(gateway.generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class))).willAnswer(invocation -> {
			jdbc.update("update chat set icebreakers=jsonb_set(icebreakers,'{generation}',"
				+ "to_jsonb(cast(? as text))) where id=?", UUID.randomUUID().toString(), room.getId());
			return syntheticResult();
		});
		request(room, first, UUID.randomUUID());
		processor.process(room.getId());
		JsonNode stored = jsonMapper.readTree(storedState(room));
		assertThat(stored.path("questions").size()).isZero();
		assertThat(stored.path("source").asString()).isNotEqualTo("GENERATED");
	}

	@Test
	void newKeyDoesNotRestartAnAlreadyTerminalFailedRound() throws Exception {
		request(room, first, UUID.randomUUID());
		JsonNode accepted = jsonMapper.readTree(storedState(room));
		jdbc.update("update chat set icebreakers=jsonb_set(icebreakers,'{status}','\"FAILED\"'::jsonb) "
			+ "where id=?", room.getId());
		JsonNode replay = request(room, second, UUID.randomUUID());
		assertThat(replay.path("status").asString()).isEqualTo("FAILED");
		processor.process(room.getId());
		JsonNode after = jsonMapper.readTree(storedState(room));
		assertThat(after.path("generation")).isEqualTo(accepted.path("generation"));
		assertThat(after.path("attempts")).isEqualTo(accepted.path("attempts"));
		assertThat(result(room, second).path("status").asString()).isEqualTo("FAILED");
		then(gateway).should(never()).generate(anyLong(), any(UUID.class), anyMap(), any(Instant.class));
	}

	private void enableSyntheticGateway() {
		given(gateway.enabled()).willReturn(true);
		Map<String, Object> manifest = Map.ofEntries(
			Map.entry("releaseId", UUID.randomUUID().toString()),
			Map.entry("surveyVersion", "synthetic"), Map.entry("wordingVersion", "synthetic"),
			Map.entry("scoringVersion", "synthetic"), Map.entry("typeModelVersion", "synthetic"),
			Map.entry("analysisVersion", "synthetic"), Map.entry("typeReferenceVersion", "synthetic"),
			Map.entry("matchingVersion", "synthetic"), Map.entry("productPolicyVersion", "synthetic"),
			Map.entry("engineVersion", "synthetic"), Map.entry("contentHash", "0".repeat(64)));
		given(gateway.manifest()).willReturn(manifest);
	}

	private IcebreakerGateway.Generated syntheticResult() {
		return new IcebreakerGateway.Generated(List.of("첫 번째 질문", "두 번째 질문", "세 번째 질문"),
			"GENERATED", "synthetic-prompt-v1");
	}

	private JsonNode newReadyUser() throws Exception {
		JsonNode tokens = login("google", newSocialId());
		completeOnboarding(tokens);
		return tokens;
	}

	private ChatRoom createRoom(long firstUser, long secondUser) {
		MatchProposal proposal = proposalRows.saveAndFlush(
			MatchProposal.proposed(firstUser, secondUser, testClock.instant()));
		participants.saveAllAndFlush(List.of(new MatchingParticipant(proposal.getId(), firstUser),
			new MatchingParticipant(proposal.getId(), secondUser)));
		proposals.decide(proposal.getId(), firstUser, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposal.getId(), firstUser).profileVersion());
		proposals.decide(proposal.getId(), secondUser, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposal.getId(), secondUser).profileVersion());
		return rooms.findBySourceProposalId(proposal.getId()).orElseThrow();
	}

	private JsonNode request(ChatRoom target, JsonNode tokens, UUID key) throws Exception {
		String response = perform(post(path(target)).header("Idempotency-Key", key), tokens, null)
			.andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
			.andReturn().getResponse().getContentAsString();
		return jsonMapper.readTree(response);
	}

	private JsonNode result(ChatRoom target, JsonNode tokens) throws Exception {
		String response = perform(get(path(target)), tokens, null).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andReturn().getResponse().getContentAsString();
		return jsonMapper.readTree(response);
	}

	private String storedState(ChatRoom target) {
		return jdbc.queryForObject("select cast(icebreakers as text) from chat where id=?", String.class,
			target.getId());
	}

	private Map<String, Object> unaffectedColumns(ChatRoom target) {
		return jdbc.queryForMap("select matching_id,created_at,expires_at,closed_at,delete_at,retain_until,"
			+ "close_reason,last_message_seq,cast(ai_state as text) from chat where id=?", target.getId());
	}

	private void withdraw(long userId, Instant at) {
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (?,'AI_RISK_DETECTION',?,false,?)", userId, AI_RISK_VERSION, Timestamp.from(at));
	}

	private String path(ChatRoom target) {
		return "/api/chat-rooms/" + target.getId() + "/icebreakers";
	}
}
