package com.e106.tingle.safety;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.chat.ChatRoom;
import com.e106.tingle.chat.ChatRoomRepository;
import com.e106.tingle.chat.ChatService;
import com.e106.tingle.chat.notice.ChatNoticeService;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.ProposalService;
import com.e106.tingle.safety.dto.ReportResult;

import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class SafetyIntegrationTest extends IntegrationTestSupport {

	@Autowired
	private ProposalRepository proposals;
	@Autowired
	private MatchingParticipantRepository participants;
	@Autowired
	private ProposalService proposalService;
	@Autowired
	private ChatRoomRepository rooms;
	@Autowired
	private ChatNoticeService notices;
	@Autowired
	private ChatService chats;

	private JsonNode first;
	private JsonNode second;
	private long firstId;
	private long secondId;
	private ChatRoom room;

	@BeforeEach
	void prepareSafetyContext() throws Exception {
		first = login("google", newSocialId());
		second = login("google", newSocialId());
		completeOnboarding(first);
		completeOnboarding(second);
		firstId = userIdOf(first);
		secondId = userIdOf(second);
		room = createRoom(firstId, secondId);
	}

	@Test
	void reportPersistsBothSendersEvidenceAndFirstNinetyDayRetention() throws Exception {
		long ownMessage = send(firstId, "own evidence");
		long partnerMessage = send(secondId, "partner evidence");
		UUID key = UUID.randomUUID();
		JsonNode result = read(report(first, key, body(room.getId(), List.of(ownMessage, partnerMessage))));
		long reportId = result.path("reportId").asLong();
		assertThat(result.path("status").asString()).isEqualTo("RECEIVED");
		assertThat(jdbc.queryForObject("select reporter_participant_id from chat_report where id=?",
			Long.class, reportId)).isEqualTo(participantId(firstId));
		assertThat(jdbc.queryForObject("select count(*) from chat_report_message where report_id=?",
			Long.class, reportId)).isEqualTo(2);
		Instant created = Instant.parse(result.path("createdAt").asString());
		assertThat(retainUntil()).isEqualTo(created.plus(Duration.ofDays(90)));
		assertThat(timestamp("closed_at")).isNull();
		assertThat(jdbc.queryForObject("select count(*) from matching_participant "
			+ "where matching_id=? and blocked_at is not null", Long.class, room.getMatchingId())).isZero();
		assertThat(receiptExists(firstId, key)).isTrue();
	}

	@Test
	void reportRejectsAnotherUsersRoomAndHidesMissingContext() throws Exception {
		JsonNode outsider = login("google", newSocialId());
		completeOnboarding(outsider);
		report(outsider, UUID.randomUUID(), body(room.getId(), List.of()))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		report(first, UUID.randomUUID(), body(Long.MAX_VALUE, List.of()))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		assertThat(reportCount()).isZero();
	}

	@Test
	void realHttpRejectsUnknownActorAndValidationFieldsWithoutSideEffects() throws Exception {
		long firstVersion = settingsVersion(firstId);
		block(first, UUID.randomUUID(), Map.of("proposalId", room.getMatchingId(), "userId", secondId))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		block(first, UUID.randomUUID(), Map.of("proposalId", room.getMatchingId(), "singleContext", true))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		report(first, UUID.randomUUID(), toJson(Map.of("chatRoomId", room.getId(), "reason", "SPAM",
			"reporterId", secondId))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		assertThat(reportCount()).isZero();
		assertThat(timestamp("closed_at")).isNull();
		assertThat(settingsVersion(firstId)).isEqualTo(firstVersion);
	}

	@Test
	void foreignEvidenceRollsBackReportRetentionAndReceipt() throws Exception {
		JsonNode third = login("google", newSocialId());
		completeOnboarding(third);
		ChatRoom other = createRoom(firstId, userIdOf(third));
		long foreign = chats.send(other.getId(), firstId, UUID.randomUUID(), UUID.randomUUID(), "foreign")
			.messageId();
		UUID key = UUID.randomUUID();
		report(first, key, body(room.getId(), List.of(foreign)))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		assertThat(reportCount()).isZero();
		assertThat(retainUntil()).isNull();
		assertThat(receiptExists(firstId, key)).isFalse();
	}

	@Test
	void successfulReportRetrySurvivesLeaveButNewReportIsRejected() throws Exception {
		UUID key = UUID.randomUUID();
		String request = body(room.getId(), List.of());
		JsonNode original = read(report(first, key, request));
		leave(first, UUID.randomUUID(), room.getId()).andExpect(status().isOk());
		assertThat(read(report(first, key, request))).isEqualTo(original);
		report(first, UUID.randomUUID(), request).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("CHAT_CLOSED"));
		assertThat(reportCount()).isEqualTo(1);
		assertThat(retainUntil()).isEqualTo(Instant.parse(original.path("createdAt").asString())
			.plus(Duration.ofDays(90)));
	}

	@Test
	void reportKeyCannotChangeBodyOrBecomeBlockCommand() throws Exception {
		UUID key = UUID.randomUUID();
		read(report(first, key, body(room.getId(), List.of())));
		report(first, key, toJson(Map.of("chatRoomId", room.getId(), "reason", "SPAM")))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
		block(first, key, Map.of("chatRoomId", room.getId())).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
		assertThat(reportCount()).isEqualTo(1);
		assertThat(timestamp("closed_at")).isNull();
	}

	@Test
	void reportKeyCannotBeReusedInAnotherOwnedRoom() throws Exception {
		UUID key = UUID.randomUUID();
		read(report(first, key, body(room.getId(), List.of())));
		JsonNode third = login("google", newSocialId());
		completeOnboarding(third);
		ChatRoom other = createRoom(firstId, userIdOf(third));
		report(first, key, body(other.getId(), List.of())).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
		assertThat(jdbc.queryForObject("select count(*) from chat_report where chat_id=?",
			Long.class, other.getId())).isZero();
	}

	@Test
	void concurrentReportRetriesPersistExactlyOneReceiptAndReport() throws Exception {
		UUID key = UUID.randomUUID();
		String request = body(room.getId(), List.of());
		List<JsonNode> results = runConcurrently(() -> read(report(first, key, request)),
			() -> read(report(first, key, request)));
		assertThat(results.getFirst()).isEqualTo(results.getLast());
		assertThat(reportCount()).isEqualTo(1);
		assertThat(receiptExists(firstId, key)).isTrue();
	}

	@Test
	void newReceiptRepositoryRecoversStoredResponseWithoutLosingProposalEvidence() throws Exception {
		UUID key = UUID.randomUUID();
		JsonNode original = read(report(first, key, body(room.getId(), List.of())));
		String saved = jdbc.queryForObject("select request_state->>cast(? as text) "
			+ "from matching_participant where matching_id=? and user_id=?", String.class,
			"safety:" + key, room.getMatchingId(), firstId);
		String hash = jsonMapper.readTree(saved).path("requestHash").asString();
		ReportResult restored = new SafetyReceipts(jdbc, jsonMapper)
			.find(firstId, key, "REPORT", hash, ReportResult.class).orElseThrow();
		assertThat(restored.reportId()).isEqualTo(original.path("reportId").asLong());
		assertThat(restored.createdAt()).isEqualTo(Instant.parse(original.path("createdAt").asString()));
		assertThat(jdbc.queryForObject("select request_state->>'command' from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, room.getMatchingId(), firstId)).isEqualTo("ACCEPT");
		assertThat(jdbc.queryForObject("select decision_request_key is not null from matching_participant "
			+ "where matching_id=? and user_id=?", Boolean.class, room.getMatchingId(), firstId)).isTrue();
	}

	@Test
	void anotherReportDoesNotExtendTheFirstRetentionDeadline() throws Exception {
		JsonNode original = read(report(first, UUID.randomUUID(), body(room.getId(), List.of())));
		Instant deadline = retainUntil();
		timeShift.set(Duration.ofMinutes(1));
		JsonNode another = read(report(second, UUID.randomUUID(), body(room.getId(), List.of())));
		assertThat(another.path("reportId").asLong()).isNotEqualTo(original.path("reportId").asLong());
		assertThat(retainUntil()).isEqualTo(deadline);
		assertThat(reportCount()).isEqualTo(2);
	}

	@Test
	void reportRepairsAnEarlierShortRetentionDeadlineToNinetyDays() throws Exception {
		jdbc.update("update chat set retain_until=? where id=?",
			Timestamp.from(clock.instant().plus(Duration.ofDays(30))), room.getId());
		JsonNode result = read(report(first, UUID.randomUUID(), body(room.getId(), List.of())));
		assertThat(retainUntil()).isEqualTo(Instant.parse(result.path("createdAt").asString())
			.plus(Duration.ofDays(90)));
	}

	@Test
	void reportPreservesAnExistingLongerRetentionPromise() throws Exception {
		jdbc.update("update chat set retain_until=? where id=?",
			Timestamp.from(clock.instant().plus(Duration.ofDays(120))), room.getId());
		Instant promised = retainUntil();
		read(report(first, UUID.randomUUID(), body(room.getId(), List.of())));
		assertThat(retainUntil()).isEqualTo(promised);
	}

	@Test
	void consentWithdrawalRejectsNewReportAndLeaveButRecoversASuccessfulReport() throws Exception {
		UUID key = UUID.randomUUID();
		String request = body(room.getId(), List.of());
		JsonNode original = read(report(first, key, request));
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (?,'AI_RISK_DETECTION',?,false,?)", firstId, AI_RISK_VERSION, Timestamp.from(clock.instant()));
		report(first, UUID.randomUUID(), request).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("CHAT_CLOSED"));
		leave(first, UUID.randomUUID(), room.getId()).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("CHAT_CLOSED"));
		assertThat(read(report(first, key, request))).isEqualTo(original);
		assertThat(reportCount()).isEqualTo(1);
		assertThat(timestamp("closed_at")).isNull();
	}

	@Test
	void sameUuidIsIndependentForDifferentMembers() throws Exception {
		UUID key = UUID.randomUUID();
		JsonNode firstReport = read(report(first, key, body(room.getId(), List.of())));
		JsonNode secondReport = read(report(second, key, body(room.getId(), List.of())));
		assertThat(firstReport.path("reportId").asLong()).isNotEqualTo(secondReport.path("reportId").asLong());
		assertThat(receiptExists(firstId, key)).isTrue();
		assertThat(receiptExists(secondId, key)).isTrue();
	}

	@Test
	void blockAtomicallyClosesBothSidesAndAdvancesVersionsOnlyOnce() throws Exception {
		long firstVersion = settingsVersion(firstId);
		long secondVersion = settingsVersion(secondId);
		List<Map<String, Object>> acknowledgements = keyringAcknowledgements();
		UUID key = UUID.randomUUID();
		JsonNode original = read(block(first, key, Map.of("chatRoomId", room.getId())));
		assertThat(original.path("proposalId").asLong()).isEqualTo(room.getMatchingId());
		assertThat(Instant.parse(original.path("blockedAt").asString())).isEqualTo(jdbc.queryForObject(
			"select blocked_at from matching_participant where matching_id=? and user_id=?",
			Timestamp.class, room.getMatchingId(), firstId).toInstant());
		assertThat(settingsVersion(firstId)).isEqualTo(firstVersion + 1);
		assertThat(settingsVersion(secondId)).isEqualTo(secondVersion + 1);
		assertThat(keyringAcknowledgements()).isEqualTo(acknowledgements);
		assertThat(read(block(first, key, Map.of("chatRoomId", room.getId())))).isEqualTo(original);
		assertThat(settingsVersion(firstId)).isEqualTo(firstVersion + 1);
		assertThat(settingsVersion(secondId)).isEqualTo(secondVersion + 1);
		assertThat(jdbc.queryForObject("select close_reason::text from chat where id=?",
			String.class, room.getId())).isEqualTo("BLOCKED");
		assertThat(jdbc.queryForList("select response::text from matching_participant where matching_id=?",
			String.class, room.getMatchingId())).containsExactlyInAnyOrder("ACCEPTED", "ACCEPTED");
		perform(get(roomPath() + "/messages"), first, null).andExpect(status().isGone());
		perform(get(roomPath() + "/messages"), second, null).andExpect(status().isGone());
		perform(get(roomPath()), second, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.canRead").value(false)).andExpect(jsonPath("$.closeReason").doesNotExist());
	}

	@Test
	void blockReplayPreservesFrozenInputsAndSettingsReadsCaptureTheNewGeneration() throws Exception {
		// 공용 가입 fixture의 직접 SQL 설정 대신 실제 저장 API로 현재 입력과 세대를 함께 확정한다.
		for (JsonNode actor : List.of(first, second)) {
			String path = me(actor, "/preferences");
			String etag = perform(get(path), actor, null).andExpect(status().isOk())
				.andReturn().getResponse().getHeader("ETag");
			perform(put(path).header("If-Match", etag), actor,
				"{\"desiredAgeMode\":\"RANGE\",\"desiredAgeMin\":19,\"desiredAgeMax\":null}")
				.andExpect(status().isOk());
		}
		Map<Long, Long> versions = Map.of(firstId, settingsVersion(firstId), secondId, settingsVersion(secondId));
		for (JsonNode actor : List.of(first, second)) {
			perform(get(me(actor, "/settings")), actor, null).andExpect(status().isOk());
		}
		List<Map<String, Object>> frozen = jdbc.queryForList("select * from settings_snapshot "
			+ "where (user_id=? and settings_version=?) or (user_id=? and settings_version=?) order by user_id",
			firstId, versions.get(firstId), secondId, versions.get(secondId));
		assertThat(frozen).hasSize(2);
		List<Map<String, Object>> acknowledgements = keyringAcknowledgements();
		UUID reportKey = UUID.randomUUID();
		String reportBody = body(room.getId(), List.of(send(firstId, "차단 전 신고 증거")));
		JsonNode reported = read(report(first, reportKey, reportBody));
		String reportReceipt = jdbc.queryForObject("select request_state->>cast(? as text) "
			+ "from matching_participant where matching_id=? and user_id=?",
			String.class, "safety:" + reportKey, room.getMatchingId(), firstId);
		UUID blockKey = UUID.randomUUID();
		Map<String, Object> blockBody = Map.of("chatRoomId", room.getId());
		JsonNode blocked = read(block(first, blockKey, blockBody));
		String receipts = jdbc.queryForObject("select request_state::text from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, room.getMatchingId(), firstId);
		assertThat(jsonMapper.readTree(receipts).path("safety:" + reportKey).asString()).isEqualTo(reportReceipt);
		assertThat(read(block(first, blockKey, blockBody))).isEqualTo(blocked);
		for (Map<String, Object> snapshot : frozen) {
			long userId = ((Number)snapshot.get("user_id")).longValue();
			long requiredVersion = versions.get(userId) + 1;
			assertThat(settingsVersion(userId)).isEqualTo(requiredVersion);
			JsonNode actor = userId == firstId ? first : second;
			perform(get(me(actor, "/settings")), actor, null)
				.andExpect(status().isOk()).andExpect(jsonPath("$.settingsVersion").value(requiredVersion));
			JsonNode current = jsonMapper.readTree(jdbc.queryForObject("select input_json from settings_snapshot "
				+ "where user_id=? and settings_version=?", String.class, userId, requiredVersion));
			JsonNode previous = jsonMapper.readTree((String)snapshot.get("input_json"));
			assertThat(current.path("settings").path("version").asLong()).isEqualTo(requiredVersion);
			assertThat(current.path("profile")).isEqualTo(previous.path("profile"));
			assertThat(current.path("preferences")).isEqualTo(previous.path("preferences"));
			assertThat(current.path("analysis")).isEqualTo(previous.path("analysis"));
			assertThat(current.path("ageReferenceDate")).isEqualTo(previous.path("ageReferenceDate"));
			assertThat(jdbc.queryForMap("select * from settings_snapshot where id=?", snapshot.get("id")))
				.isEqualTo(snapshot);
			assertThat(settingsVersion(userId)).isEqualTo(requiredVersion);
		}
		assertThat(read(report(first, reportKey, reportBody))).isEqualTo(reported);
		assertThat(jdbc.queryForObject("select request_state::text from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, room.getMatchingId(), firstId)).isEqualTo(receipts);
		assertThat(keyringAcknowledgements()).isEqualTo(acknowledgements);
	}

	@Test
	void concurrentBlockRetriesIncreaseEachSettingsVersionOnce() throws Exception {
		long firstVersion = settingsVersion(firstId);
		long secondVersion = settingsVersion(secondId);
		UUID key = UUID.randomUUID();
		List<JsonNode> results = runConcurrently(
			() -> read(block(first, key, Map.of("proposalId", room.getMatchingId()))),
			() -> read(block(first, key, Map.of("proposalId", room.getMatchingId()))));
		assertThat(results.getFirst()).isEqualTo(results.getLast());
		assertThat(settingsVersion(firstId)).isEqualTo(firstVersion + 1);
		assertThat(settingsVersion(secondId)).isEqualTo(secondVersion + 1);
	}

	@Test
	void blockRemovesPendingProposalFromListSingleAndPublicProfileAccess() throws Exception {
		JsonNode third = login("google", newSocialId());
		completeOnboarding(third);
		long thirdId = userIdOf(third);
		MatchProposal pending = proposals.saveAndFlush(MatchProposal.proposed(firstId, thirdId, clock.instant()));
		participants.saveAllAndFlush(List.of(new MatchingParticipant(pending.getId(), firstId),
			new MatchingParticipant(pending.getId(), thirdId)));
		String proposalPath = "/api/match-proposals/" + pending.getId();
		perform(get(proposalPath + "/partner-profile"), first, null).andExpect(status().isOk());
		read(block(first, UUID.randomUUID(), Map.of("proposalId", pending.getId())));
		perform(get(proposalPath), first, null).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("PROPOSAL_EXPIRED"));
		perform(get(proposalPath), third, null).andExpect(status().isGone());
		perform(get(proposalPath + "/partner-profile"), first, null).andExpect(status().isGone());
		JsonNode page = jsonMapper.readTree(perform(get("/api/match-proposals"), first, null)
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		for (JsonNode item : page.path("items")) {
			assertThat(item.path("proposalId").asLong()).isNotEqualTo(pending.getId());
		}
	}

	@Test
	void missingPartnerSettingRollsBackBlockAndRoomClosure() throws Exception {
		long originalVersion = settingsVersion(firstId);
		jdbc.update("delete from user_setting where user_id=?", secondId);
		UUID key = UUID.randomUUID();
		block(first, key, Map.of("chatRoomId", room.getId())).andExpect(status().isServiceUnavailable());
		assertThat(settingsVersion(firstId)).isEqualTo(originalVersion);
		assertThat(timestamp("closed_at")).isNull();
		assertThat(jdbc.queryForObject("select blocked_at from matching_participant "
			+ "where matching_id=? and user_id=?", Timestamp.class, room.getMatchingId(), firstId)).isNull();
		assertThat(receiptExists(firstId, key)).isFalse();
	}

	@Test
	void leaveClosesBothSidesAndRecoversItsFirstEffectiveTime() throws Exception {
		UUID key = UUID.randomUUID();
		JsonNode original = read(leave(first, key, room.getId()));
		Instant leftAt = Instant.parse(original.path("leftAt").asString());
		assertThat(original.path("rematchingRestricted").asBoolean()).isTrue();
		assertThat(timestamp("closed_at")).isEqualTo(leftAt);
		assertThat(jdbc.queryForObject("select left_at from chat_participant where id=?",
			Timestamp.class, participantId(firstId)).toInstant()).isEqualTo(leftAt);
		assertThat(jdbc.queryForObject("select left_at from chat_participant where id=?",
			Timestamp.class, participantId(secondId))).isNull();
		assertThat(jdbc.queryForObject("select close_reason::text from chat where id=?",
			String.class, room.getId())).isEqualTo("LEFT");
		assertThat(read(leave(first, key, room.getId()))).isEqualTo(original);
		perform(get(roomPath() + "/messages"), first, null).andExpect(status().isGone());
		perform(get(roomPath() + "/messages"), second, null).andExpect(status().isGone());
	}

	@Test
	void leavePreservesAtLeastThirtyDaysDespiteEarlierStoredDeletionDates() throws Exception {
		Instant now = clock.instant();
		jdbc.update("update chat set delete_at=?,retain_until=? where id=?",
			Timestamp.from(now.plus(Duration.ofDays(1))), Timestamp.from(now.plus(Duration.ofDays(2))), room.getId());
		JsonNode result = read(leave(first, UUID.randomUUID(), room.getId()));
		Instant leftAt = Instant.parse(result.path("leftAt").asString());
		assertThat(timestamp("delete_at")).isEqualTo(leftAt.plus(Duration.ofDays(30)));
	}

	@Test
	void leaveCannotOverwriteAnEarlierBlock() throws Exception {
		read(block(first, UUID.randomUUID(), Map.of("chatRoomId", room.getId())));
		Instant firstClose = timestamp("closed_at");
		leave(second, UUID.randomUUID(), room.getId()).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("CHAT_CLOSED"));
		assertThat(timestamp("closed_at")).isEqualTo(firstClose);
		assertThat(jdbc.queryForObject("select close_reason::text from chat where id=?",
			String.class, room.getId())).isEqualTo("BLOCKED");
	}

	@Test
	void expiredRoomRejectsNewSafetyReportAndLeaveAtTheExactBoundary() throws Exception {
		jdbc.update("update chat set expires_at=? where id=?", Timestamp.from(clock.instant()), room.getId());
		report(first, UUID.randomUUID(), body(room.getId(), List.of())).andExpect(status().isGone());
		leave(first, UUID.randomUUID(), room.getId()).andExpect(status().isGone());
		assertThat(reportCount()).isZero();
		assertThat(timestamp("closed_at")).isNull();
	}

	@Test
	void concurrentReportAndLeaveNeverCreateAReportAfterClosure() throws Exception {
		UUID reportKey = UUID.randomUUID();
		String request = body(room.getId(), List.of());
		List<Integer> results = runConcurrently(
			() -> report(first, reportKey, request).andReturn().getResponse().getStatus(),
			() -> leave(first, UUID.randomUUID(), room.getId()).andReturn().getResponse().getStatus());
		assertThat(results.get(0)).isIn(201, 410);
		assertThat(results.get(1)).isEqualTo(200);
		if (results.getFirst() == 201) {
			assertThat(reportCount()).isEqualTo(1);
			report(first, reportKey, request).andExpect(status().isCreated());
		} else {
			assertThat(reportCount()).isZero();
			assertThat(retainUntil()).isNull();
		}
	}

	@Test
	void revokedSessionCannotReuseAPersistedReportReceipt() throws Exception {
		UUID key = UUID.randomUUID();
		String request = body(room.getId(), List.of());
		read(report(first, key, request));
		logout(first.path("refreshToken").asString()).andExpect(status().isNoContent());
		report(first, key, request).andExpect(status().isUnauthorized());
		assertThat(reportCount()).isEqualTo(1);
	}

	private ChatRoom createRoom(long firstUserId, long secondUserId) {
		MatchProposal proposal = proposals.saveAndFlush(MatchProposal.proposed(firstUserId, secondUserId,
			clock.instant()));
		participants.saveAllAndFlush(List.of(new MatchingParticipant(proposal.getId(), firstUserId),
			new MatchingParticipant(proposal.getId(), secondUserId)));
		proposalService.decide(proposal.getId(), firstUserId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposalService.profile(proposal.getId(), firstUserId).profileVersion());
		proposalService.decide(proposal.getId(), secondUserId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposalService.profile(proposal.getId(), secondUserId).profileVersion());
		ChatRoom saved = rooms.findBySourceProposalId(proposal.getId()).orElseThrow();
		for (long userId : List.of(firstUserId, secondUserId)) {
			var entry = notices.enter(saved.getId(), userId, UUID.randomUUID());
			for (var notice : entry.notices()) {
				notices.ack(saved.getId(), notice.noticeId(), userId, UUID.randomUUID());
			}
		}
		return saved;
	}

	private long send(long userId, String text) {
		return chats.send(room.getId(), userId, UUID.randomUUID(), UUID.randomUUID(), text).messageId();
	}

	private ResultActions report(JsonNode actor, UUID key, String body) throws Exception {
		return perform(post("/api/reports").header("Idempotency-Key", key), actor, body);
	}

	private ResultActions block(JsonNode actor, UUID key, Map<String, Object> body) throws Exception {
		return perform(post("/api/blocks").header("Idempotency-Key", key), actor, toJson(body));
	}

	private ResultActions leave(JsonNode actor, UUID key, long roomId) throws Exception {
		return perform(post("/api/chat-rooms/" + roomId + "/leave").header("Idempotency-Key", key), actor, null);
	}

	private String body(long roomId, List<Long> messages) throws Exception {
		return toJson(Map.of("chatRoomId", roomId, "reason", "ABUSE", "messageIds", messages));
	}

	private JsonNode read(ResultActions result) throws Exception {
		result.andExpect(status().is2xxSuccessful()).andExpect(header().string("Cache-Control", "no-store"));
		return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private long participantId(long userId) {
		return jdbc.queryForObject("select id from chat_participant where chat_id=? and user_id=?",
			Long.class, room.getId(), userId);
	}

	private long reportCount() {
		return jdbc.queryForObject("select count(*) from chat_report where chat_id=?", Long.class, room.getId());
	}

	private long settingsVersion(long userId) {
		return jdbc.queryForObject("select settings_version from user_setting where user_id=?", Long.class, userId);
	}

	private List<Map<String, Object>> keyringAcknowledgements() {
		return jdbc.queryForList("select id,owner_user_id,state,synced_settings_version,synced_at "
			+ "from keyring where owner_user_id in (?,?) order by id", firstId, secondId);
	}

	private Instant retainUntil() {
		return timestamp("retain_until");
	}

	private Instant timestamp(String column) {
		Timestamp value = jdbc.queryForObject("select " + column + " from chat where id=?",
			Timestamp.class, room.getId());
		return value == null ? null : value.toInstant();
	}

	private boolean receiptExists(long userId, UUID key) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from matching_participant "
			+ "where user_id=? and jsonb_exists(request_state,?))", Boolean.class, userId, "safety:" + key));
	}

	private String roomPath() {
		return "/api/chat-rooms/" + room.getId();
	}
}
