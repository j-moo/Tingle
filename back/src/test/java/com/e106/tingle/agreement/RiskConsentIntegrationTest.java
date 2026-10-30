package com.e106.tingle.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.chat.ChatRoomRepository;
import com.e106.tingle.chat.ChatService;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.ProposalService;
import com.e106.tingle.risk.RiskAnalysisGateway;
import com.e106.tingle.risk.RiskAnalysisStore;
import com.e106.tingle.risk.RiskAnalysisTypes.Audience;
import com.e106.tingle.risk.RiskAnalysisTypes.Category;
import com.e106.tingle.risk.RiskAnalysisTypes.Finding;
import com.e106.tingle.risk.RiskAnalysisTypes.Kind;
import com.e106.tingle.risk.RiskAnalysisTypes.Manifest;
import com.e106.tingle.risk.RiskAnalysisTypes.NoticeType;
import com.e106.tingle.risk.RiskAnalysisTypes.Request;
import com.e106.tingle.risk.RiskAnalysisTypes.Verdict;

import tools.jackson.databind.JsonNode;

/** Real JWT/HTTP and PostgreSQL checks; onboarding uses the existing explicit social/AI/ACK fixtures. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class RiskConsentIntegrationTest extends IntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-10-29T06:00:00Z");
	private static final String PATH = "/risk-analysis-consent";

	@Autowired
	private RiskConsentService service;
	@Autowired
	private AgreementProperties properties;
	@Autowired
	private Clock testClock;
	@Autowired
	private ChatService chats;
	@Autowired
	private ChatRoomRepository rooms;
	@Autowired
	private MemberAccessRepository members;
	@Autowired
	private PlatformTransactionManager transactions;
	@Autowired
	private ProposalService proposals;
	@Autowired
	private ProposalRepository proposalRows;
	@Autowired
	private MatchingParticipantRepository matchingParticipants;
	@Autowired
	private RiskAnalysisStore analysis;
	@MockitoBean
	private RiskAnalysisGateway gateway;

	@BeforeEach
	void fixedConsentClock() {
		given(testClock.instant()).willReturn(NOW);
	}

	@Test
	void missingConsentIsDisabledAndNeverInferredFromAggregateAgreementTime() throws Exception {
		JsonNode tokens = login("google", newSocialId());
		long userId = userIdOf(tokens);
		jdbc.update("update users set agreed_at=? where id=?", timestamp(NOW), userId);
		perform(get(me(tokens, PATH)), tokens, null).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.enabled").value(false))
			.andExpect(jsonPath("$.noticeVersion").value(version()))
			.andExpect(jsonPath("$.grantedAt").isEmpty());
		assertThat(consentCount(userId)).isZero();
	}

	@Test
	void realAuthenticationOwnershipAndStrictJsonKeepTheirErrorCodes() throws Exception {
		JsonNode tokens = onboard();
		long userId = userIdOf(tokens);
		mockMvc.perform(get("/api/users/{userId}" + PATH, userId)).andExpect(status().isUnauthorized());
		perform(get("/api/users/{userId}" + PATH, userId + 1), tokens, null)
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		perform(put("/api/users/{userId}" + PATH, userId + 1), tokens, body(false, version()))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		long before = consentCount(userId);
		for (String invalid : List.of("{}", "null", "{\"enabled\":null,\"noticeVersion\":\"" + version() + "\"}",
			"{\"enabled\":false,\"noticeVersion\":null}", "{\"enabled\":false,\"noticeVersion\":\" \"}",
			"{\"enabled\":false,\"noticeVersion\":\"" + "x".repeat(101) + "\"}",
			"{\"enabled\":false,\"noticeVersion\":\"" + version() + "\",\"userId\":1}")) {
			perform(put(me(tokens, PATH)), tokens, invalid).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
		assertThat(consentCount(userId)).isEqualTo(before);
	}

	@Test
	void staleDocumentCannotGrantOrWithdrawAndDoesNotChangeStoredState() throws Exception {
		JsonNode tokens = onboard();
		long userId = userIdOf(tokens);
		long before = consentCount(userId);
		for (boolean enabled : List.of(true, false)) {
			perform(put(me(tokens, PATH)), tokens, body(enabled, "outdated"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("AGREEMENT_VERSION_OUTDATED"));
		}
		assertThat(consentCount(userId)).isEqualTo(before);
		assertThat(service.get(userId).enabled()).isTrue();
	}

	@Test
	void currentGrantRetriesPreserveOriginalConsentTimeAndWithdrawalAddsOneHistoryRow() throws Exception {
		JsonNode tokens = onboard();
		long userId = userIdOf(tokens);
		Instant firstGrant = service.get(userId).grantedAt();
		long before = consentCount(userId);
		given(testClock.instant()).willReturn(NOW.plusSeconds(30));
		perform(put(me(tokens, PATH)), tokens, body(true, version())).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.enabled").value(true))
			.andExpect(jsonPath("$.grantedAt").value(firstGrant.toString()));
		assertThat(consentCount(userId)).isEqualTo(before);
		perform(put(me(tokens, PATH)), tokens, body(false, version())).andExpect(status().isOk())
			.andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.grantedAt").isEmpty());
		given(testClock.instant()).willReturn(NOW.plusSeconds(60));
		service.update(userId, false, version());
		assertThat(consentCount(userId)).isEqualTo(before + 1);
		assertThat(jdbc.queryForObject("select recorded_at from user_consents where user_id=? "
			+ "and consent_type='AI_RISK_DETECTION' and not agreed", Timestamp.class, userId))
			.isEqualTo(timestamp(NOW.plusSeconds(30)));
	}

	@Test
	void obsoleteVersionIsNotActiveEvenWhenItsLastStoredAnswerWasTrue() throws Exception {
		JsonNode tokens = onboard();
		long userId = userIdOf(tokens);
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (?,'AI_RISK_DETECTION','obsolete',true,?)", userId, timestamp(NOW.plusSeconds(1)));
		assertThat(service.get(userId).enabled()).isFalse();
		assertThat(service.get(userId).noticeVersion()).isEqualTo(version());
		assertThat(service.get(userId).grantedAt()).isNull();
	}

	@Test
	void withdrawalClosesEveryActiveRoomAndKeepsOtherMatchingReadinessUntouched() throws Exception {
		JsonNode owner = onboard();
		long userId = userIdOf(owner);
		long otherId = userIdOf(onboard());
		long thirdId = userIdOf(onboard());
		long firstRoom = createRoom(userId, otherId);
		long secondRoom = createRoom(userId, thirdId);
		Map<String, Object> settings = jdbc.queryForMap("select * from user_setting where user_id=?", userId);
		Map<String, Object> preferences = jdbc.queryForMap(
			"select * from matching_preference where user_id=?", userId);
		service.update(userId, false, version());
		for (long roomId : List.of(firstRoom, secondRoom)) {
			assertThat(jdbc.queryForMap(
				"select closed_at,close_reason::text,delete_at from chat where id=?", roomId))
				.containsEntry("closed_at", timestamp(NOW)).containsEntry("close_reason", "LEFT")
				.containsEntry("delete_at", timestamp(NOW.plus(Duration.ofDays(30))));
			assertThat(jdbc.queryForObject("select left_at from chat_participant where chat_id=? and user_id=?",
				Timestamp.class, roomId, userId)).isEqualTo(timestamp(NOW));
			assertThat(jdbc.queryForObject("select status::text from matching m join chat c on c.matching_id=m.id "
				+ "where c.id=?", String.class, roomId)).isEqualTo("CLOSED");
			assertThat(chats.available(rooms.findById(roomId).orElseThrow(), participantOther(roomId, userId)))
				.isFalse();
		}
		assertThat(members.restrictedPair(userId, otherId)).isTrue();
		assertThat(members.restrictedPair(userId, thirdId)).isTrue();
		assertThat(members.canRespondToProposal(userId)).isTrue();
		assertThat(jdbc.queryForMap("select * from user_setting where user_id=?", userId)).isEqualTo(settings);
		assertThat(jdbc.queryForMap("select * from matching_preference where user_id=?", userId))
			.isEqualTo(preferences);
		assertThat(jdbc.queryForObject("select count(*) from matching_participant where user_id=? "
			+ "and blocked_at is not null", Long.class, userId)).isZero();
	}

	@Test
	void retentionAndPreviouslyClosedOrExpiredRoomsKeepTheirOriginalTimes() throws Exception {
		JsonNode owner = onboard();
		long userId = userIdOf(owner);
		long reported = createRoom(userId, userIdOf(onboard()));
		long closed = createRoom(userId, userIdOf(onboard()));
		long expired = createRoom(userId, userIdOf(onboard()));
		Instant retained = NOW.plus(Duration.ofDays(90));
		jdbc.update("update chat set retain_until=? where id=?", timestamp(retained), reported);
		jdbc.update("update chat set closed_at=?,close_reason='BLOCKED',delete_at=? where id=?",
			timestamp(NOW.minusSeconds(120)), timestamp(NOW.plus(Duration.ofDays(29))), closed);
		jdbc.update("update chat set created_at=?,expires_at=? where id=?",
			timestamp(NOW.minus(Duration.ofDays(7))), timestamp(NOW), expired);
		Map<String, Object> closedBefore = jdbc.queryForMap("select * from chat where id=?", closed);
		Map<String, Object> expiredBefore = jdbc.queryForMap("select * from chat where id=?", expired);
		service.update(userId, false, version());
		assertThat(jdbc.queryForObject("select delete_at from chat where id=?", Timestamp.class, reported))
			.isEqualTo(timestamp(retained));
		assertThat(jdbc.queryForObject("select retain_until from chat where id=?", Timestamp.class, reported))
			.isEqualTo(timestamp(retained));
		assertThat(jdbc.queryForMap("select * from chat where id=?", closed)).isEqualTo(closedBefore);
		assertThat(jdbc.queryForMap("select * from chat where id=?", expired)).isEqualTo(expiredBefore);
		given(testClock.instant()).willReturn(NOW.plusSeconds(60));
		service.update(userId, false, version());
		assertThat(jdbc.queryForObject("select closed_at from chat where id=?", Timestamp.class, reported))
			.isEqualTo(timestamp(NOW));
		assertThat(jdbc.queryForObject("select delete_at from chat where id=?", Timestamp.class, reported))
			.isEqualTo(timestamp(retained));
	}

	@Test
	void repeatedWithdrawalDoesNotCloseNewRoomAndRegrantOnlyOpensStillValidNewRoom() throws Exception {
		JsonNode owner = onboard();
		long userId = userIdOf(owner);
		long oldPartner = userIdOf(onboard());
		long oldRoom = createRoom(userId, oldPartner);
		service.update(userId, false, version());
		given(testClock.instant()).willReturn(NOW.plusSeconds(1));
		long newPartner = userIdOf(onboard());
		long newRoom = createRoom(userId, newPartner, NOW.plusSeconds(1));
		long history = consentCount(userId);
		service.update(userId, false, version());
		assertThat(consentCount(userId)).isEqualTo(history);
		assertThat(jdbc.queryForObject("select closed_at from chat where id=?", Timestamp.class, newRoom))
			.isNull();
		assertThat(chats.available(rooms.findById(newRoom).orElseThrow(), userId)).isFalse();
		assertThat(chats.available(rooms.findById(newRoom).orElseThrow(), newPartner)).isTrue();
		given(testClock.instant()).willReturn(NOW.plusSeconds(2));
		service.update(userId, true, version());
		assertThat(chats.available(rooms.findById(newRoom).orElseThrow(), userId)).isTrue();
		assertThat(chats.available(rooms.findById(oldRoom).orElseThrow(), userId)).isFalse();
		assertThat(members.restrictedPair(userId, oldPartner)).isTrue();
		assertThat(jdbc.queryForObject("select expires_at from chat where id=?", Timestamp.class, newRoom))
			.isEqualTo(timestamp(NOW.plusSeconds(1).plus(Duration.ofDays(7))));
	}

	@Test
	void concurrentWithdrawalsPersistOneTransitionAndOneClosure() throws Exception {
		long userId = userIdOf(onboard());
		long roomId = createRoom(userId, userIdOf(onboard()));
		long before = consentCount(userId);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Callable<Boolean> withdraw = () -> {
				ready.countDown();
				if (!start.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Withdrawal race did not start");
				}
				return service.update(userId, false, version()).enabled();
			};
			Future<Boolean> first = executor.submit(withdraw);
			Future<Boolean> second = executor.submit(withdraw);
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(first.get(15, TimeUnit.SECONDS)).isFalse();
			assertThat(second.get(15, TimeUnit.SECONDS)).isFalse();
		}
		assertThat(consentCount(userId)).isEqualTo(before + 1);
		assertThat(jdbc.queryForObject("select closed_at from chat where id=?", Timestamp.class, roomId))
			.isEqualTo(timestamp(NOW));
	}

	@Test
	void deletedChatDoesNotRemoveThePersistentRematchingRestriction() throws Exception {
		long userId = userIdOf(onboard());
		long partner = userIdOf(onboard());
		long roomId = createRoom(userId, partner);
		long matchingId = jdbc.queryForObject("select matching_id from chat where id=?", Long.class, roomId);
		service.update(userId, false, version());
		given(testClock.instant()).willReturn(NOW.plus(Duration.ofDays(31)));
		// Simulate completed content retention; this does not exercise the separate deletion worker.
		jdbc.update("delete from chat_participant where chat_id=?", roomId);
		jdbc.update("delete from chat where id=?", roomId);
		assertThat(members.restrictedPair(userId, partner)).isTrue();
		service.update(userId, true, version());
		assertThat(members.restrictedPair(userId, partner)).isTrue();
		assertThat(jdbc.queryForObject("select status::text from matching where id=?", String.class, matchingId))
			.isEqualTo("CLOSED");
		assertThat(rooms.findBySourceProposalId(matchingId)).isEmpty();
	}

	@Test
	void withdrawalWaitingOnSecondAcceptanceClosesTheRoomCreatedByThatAcceptance() throws Exception {
		long userId = userIdOf(onboard());
		long partner = userIdOf(onboard());
		MatchProposal proposed = proposalRows.saveAndFlush(MatchProposal.proposed(userId, partner, NOW));
		long matchingId = proposed.getId();
		matchingParticipants.saveAllAndFlush(List.of(new MatchingParticipant(matchingId, userId),
			new MatchingParticipant(matchingId, partner)));
		proposals.decide(matchingId, userId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(matchingId, userId).profileVersion());
		assertThat(rooms.findBySourceProposalId(matchingId)).isEmpty();
		CountDownLatch acceptanceLocked = new CountDownLatch(1);
		CountDownLatch allowAcceptance = new CountDownLatch(1);
		CountDownLatch withdrawalConnected = new CountDownLatch(1);
		AtomicInteger acceptancePid = new AtomicInteger();
		AtomicInteger withdrawalPid = new AtomicInteger();
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<?> acceptance = executor.submit(() -> new TransactionTemplate(transactions)
				.executeWithoutResult(transaction -> {
					members.lockMember(partner);
					jdbc.queryForObject("select id from matching where id=? for update", Long.class, matchingId);
					acceptancePid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
					acceptanceLocked.countDown();
					awaitLatch(allowAcceptance);
					proposals.decide(matchingId, partner, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
						proposals.profile(matchingId, partner).profileVersion());
				}));
			assertThat(acceptanceLocked.await(10, TimeUnit.SECONDS)).isTrue();
			Future<?> withdrawal = executor.submit(() -> new TransactionTemplate(transactions)
				.executeWithoutResult(transaction -> {
					withdrawalPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
					withdrawalConnected.countDown();
					service.update(userId, false, version());
				}));
			try {
				assertThat(withdrawalConnected.await(10, TimeUnit.SECONDS)).isTrue();
				assertBlockedBy(withdrawalPid.get(), acceptancePid.get());
			} finally {
				allowAcceptance.countDown();
			}
			acceptance.get(15, TimeUnit.SECONDS);
			withdrawal.get(15, TimeUnit.SECONDS);
		}
		long roomId = rooms.findBySourceProposalId(matchingId).orElseThrow().getId();
		assertThat(jdbc.queryForObject("select close_reason::text from chat where id=?", String.class, roomId))
			.isEqualTo("LEFT");
		assertThat(jdbc.queryForObject("select closed_at from chat where id=?", Timestamp.class, roomId))
			.isEqualTo(timestamp(NOW));
		assertThat(service.get(userId).enabled()).isFalse();
		assertThat(members.restrictedPair(userId, partner)).isTrue();
	}

	@Test
	void callerTransactionRollbackRestoresConsentAndEveryRoomTogether() throws Exception {
		long userId = userIdOf(onboard());
		long partner = userIdOf(onboard());
		long roomId = createRoom(userId, partner);
		long before = consentCount(userId);
		Map<String, Object> roomBefore = jdbc.queryForMap("select * from chat where id=?", roomId);
		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			service.update(userId, false, version());
			throw new IllegalStateException("rollback the owning transaction");
		})).isInstanceOf(IllegalStateException.class).hasMessage("rollback the owning transaction");
		assertThat(service.get(userId).enabled()).isTrue();
		assertThat(consentCount(userId)).isEqualTo(before);
		assertThat(jdbc.queryForMap("select * from chat where id=?", roomId)).isEqualTo(roomBefore);
		assertThat(jdbc.queryForObject("select left_at from chat_participant where chat_id=? and user_id=?",
			Timestamp.class, roomId, userId)).isNull();
		assertThat(members.restrictedPair(userId, partner)).isFalse();
	}

	@Test
	void actualWithdrawalAndRegrantDiscardAnAlreadyClaimedRiskResult() throws Exception {
		long userId = userIdOf(onboard());
		long partner = userIdOf(onboard());
		long roomId = createRoom(userId, partner);
		Instant retainedUntil = NOW.plus(Duration.ofDays(90));
		jdbc.update("update chat set retain_until=?,delete_at=?,last_message_seq=1 where id=?",
			timestamp(retainedUntil), timestamp(retainedUntil), roomId);
		long sender = jdbc.queryForObject("select id from chat_participant where chat_id=? and user_id=?",
			Long.class, roomId, userId);
		jdbc.update("insert into chat_message(chat_id,sender_participant_id,client_message_id,request_key,"
			+ "request_hash,seq,body,created_at) values (?,?,?,?,?,?,?,?)", roomId, sender, UUID.randomUUID(),
			UUID.randomUUID(), new byte[32], 1L, "검증용 가상 대화", timestamp(NOW.minusSeconds(1)));
		given(gateway.countTokens(any())).willReturn(100);
		String version = "synthetic-v1";
		Manifest manifest = new Manifest(UUID.fromString("11111111-1111-4111-8111-111111111111"),
			version, version, version, version, version, version, version, version, version, "0".repeat(64));
		Request request = new Request(UUID.randomUUID(), roomId, manifest);
		RiskAnalysisStore.Claim claim = analysis.claim(request);
		assertThat(claim.input()).isNotNull();

		service.update(userId, false, version());
		service.update(userId, true, version());
		Verdict late = new Verdict(Kind.RISK,
			List.of(new Finding(1L, Category.FRAUD, NoticeType.MONEY_REQUEST, Audience.RECIPIENT)),
			0.8, "synthetic-model-v1", "synthetic-prompt-v1");
		assertThat(analysis.finish(claim, late).status()).isEqualTo("FAILED");
		assertThat(service.get(userId).enabled()).isTrue();
		assertThat(jdbc.queryForObject("select close_reason::text from chat where id=?", String.class, roomId))
			.isEqualTo("LEFT");
		assertThat(jdbc.queryForObject("select delete_at from chat where id=?", Timestamp.class, roomId))
			.isEqualTo(timestamp(retainedUntil));
		assertThat(jdbc.queryForObject("select count(*) from chat_safety_notice n join chat_participant p "
			+ "on p.id=n.chat_participant_id where p.chat_id=?", Long.class, roomId)).isZero();
		assertThat(jdbc.queryForObject("select (ai_state->'riskAnalysis'->>'lastAnalyzedSeq')::bigint "
			+ "from chat where id=?", Long.class, roomId)).isZero();
		assertThat(members.restrictedPair(userId, partner)).isTrue();
		assertThatThrownBy(() -> analysis.claim(request)).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_CLOSED));
		verify(gateway, never()).analyze(any());
		verify(gateway, never()).analyze(any(), any());
	}

	private JsonNode onboard() throws Exception {
		JsonNode tokens = login("google", newSocialId());
		completeOnboarding(tokens);
		return tokens;
	}

	private String version() {
		return properties.items().stream().filter(item -> "AI_RISK_DETECTION".equals(item.type()))
			.map(AgreementProperties.Item::version).findFirst().orElseThrow();
	}

	private long consentCount(long userId) {
		return jdbc.queryForObject("select count(*) from user_consents where user_id=? "
			+ "and consent_type='AI_RISK_DETECTION'", Long.class, userId);
	}

	private long createRoom(long first, long second) {
		return createRoom(first, second, NOW.minus(Duration.ofHours(1)));
	}

	private long createRoom(long first, long second, Instant createdAt) {
		// Canonical storage fixture, not a claim that matching/AI/real-device integrations were exercised.
		long matchingId = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,status,proposed_at) "
			+ "values (?,?,'ACCEPTED',?) returning id", Long.class, Math.min(first, second),
			Math.max(first, second), timestamp(createdAt));
		for (long userId : List.of(first, second)) {
			jdbc.update("insert into matching_participant(matching_id,user_id,response,responded_at,"
				+ "decision_request_key) values (?,?,'ACCEPTED',?,?)", matchingId, userId, timestamp(createdAt),
				UUID.randomUUID());
		}
		long roomId = jdbc.queryForObject("insert into chat(matching_id,created_at,expires_at,last_message_seq,"
			+ "icebreakers,ai_state) values (?,?,?,0,'{}','{}') returning id", Long.class, matchingId,
			timestamp(createdAt), timestamp(createdAt.plus(Duration.ofDays(7))));
		for (long userId : List.of(first, second)) {
			jdbc.update("insert into chat_participant(chat_id,user_id) values (?,?)", roomId, userId);
		}
		return roomId;
	}

	private long participantOther(long roomId, long userId) {
		return jdbc.queryForObject("select user_id from chat_participant where chat_id=? and user_id<>?",
			Long.class, roomId, userId);
	}

	private static String body(boolean enabled, String version) {
		return "{\"enabled\":" + enabled + ",\"noticeVersion\":\"" + version + "\"}";
	}

	private static Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}

	private void assertBlockedBy(int waitingPid, int blockingPid) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		do {
			boolean blocked = Boolean.TRUE.equals(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",
				Boolean.class, blockingPid, waitingPid));
			if (blocked) {
				return;
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
		} while (System.nanoTime() < deadline);
		throw new AssertionError("Withdrawal did not wait for the transaction accepting the matching");
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			if (!latch.await(15, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Acceptance was not released");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Acceptance was interrupted", exception);
		}
	}
}
