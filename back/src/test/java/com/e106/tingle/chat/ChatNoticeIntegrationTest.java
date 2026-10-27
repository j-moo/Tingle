package com.e106.tingle.chat;

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
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.chat.dto.MessageView;
import com.e106.tingle.chat.notice.ChatNoticeJob;
import com.e106.tingle.chat.notice.ChatNoticeJobRepository;
import com.e106.tingle.chat.notice.ChatNoticeService;
import com.e106.tingle.chat.notice.NoticeDeliveryStore;
import com.e106.tingle.chat.notice.NoticeDispatcher;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.PairRelation;
import com.e106.tingle.proposal.PairRelationRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.ProposalService;
import com.e106.tingle.push.PushGateway;
import com.e106.tingle.push.PushInstallationRepository;
import com.e106.tingle.push.PushInstallationService;
import com.e106.tingle.push.PushPlatform;
import com.e106.tingle.push.dto.RegisterPushRequest;

import tools.jackson.databind.JsonNode;

@SpringBootTest(properties = "app.push.token-encryption-key=MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA=")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class ChatNoticeIntegrationTest extends IntegrationTestSupport {
	@Autowired
	private ChatService chats;
	@Autowired
	private ChatNoticeService notices;
	@Autowired
	private ChatNoticeJobRepository jobs;
	@Autowired
	private NoticeDispatcher dispatcher;
	@Autowired
	private NoticeDeliveryStore delivery;
	@Autowired
	private PushInstallationService installations;
	@Autowired
	private PushInstallationRepository installationRows;
	@MockitoBean
	private PushGateway gateway;
	@Autowired
	private ChatRoomRepository rooms;
	@Autowired
	private ProposalService proposals;
	@Autowired
	private ProposalRepository proposalRows;
	@Autowired
	private PairRelationRepository pairs;
	@Autowired
	private Clock testClock;
	private JsonNode first;
	private JsonNode second;
	private long firstId;
	private long secondId;
	private long proposalId;
	private ChatRoom room;

	@BeforeEach
	void prepareChat() throws Exception {
		jdbc.update("delete from background_jobs where job_type in ('FIRST_ENTRY','END_NOTICE')");
		given(gateway.ready()).willReturn(true);
		given(gateway.send(anyString(), anyString(),
			anyMap(), any())).willReturn(PushGateway.Result.ACCEPTED);
		first = login("google", newSocialId());
		second = login("google", newSocialId());
		completeOnboarding(first);
		completeOnboarding(second);
		firstId = userIdOf(first);
		secondId = userIdOf(second);
		room = createRoom(firstId, secondId);
	}

	private ChatRoom createRoom(long firstUserId, long secondUserId) {
		PairRelation pair = pairs.saveAndFlush(new PairRelation(firstUserId, secondUserId));
		Instant now = testClock.instant();
		Long encounter = jdbc.queryForObject("insert into encounters(pair_id,event_key,occurred_at,"
			+ "opportunity_expires_at,counterpart_deadline_at,status) values (?,?,?,?,?,'VERIFIED') "
			+ "returning encounter_id", Long.class, pair.getId(), UUID.randomUUID().toString(),
			Timestamp.from(now),
			Timestamp.from(now.plus(Duration.ofDays(7))), Timestamp.from(now.plus(Duration.ofDays(7))));
		MatchProposal proposal = proposalRows.saveAndFlush(new MatchProposal(pair.getId(), encounter, now));
		proposalId = proposal.getId();
		proposals.decide(proposalId, firstUserId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposalId, firstUserId).profileVersion());
		assertThat(rooms.findBySourceProposalId(proposalId)).isEmpty();
		proposals.decide(proposalId, secondUserId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposalId, secondUserId).profileVersion());
		return rooms.findBySourceProposalId(proposalId).orElseThrow();
	}

	@Test
	void entryNoticeIsIndividualStableAndRequiredBeforeMessages() throws Exception {
		var entry = notices.enter(room.getPublicId(), firstId, UUID.randomUUID());
		var firstNotice = entry.notices().getFirst();
		assertThat(entry.chatRoom().canSend()).isFalse();
		assertCode(() -> chats.messages(room.getPublicId(), firstId, null, 20), ErrorCode.CHAT_NOTICE_REQUIRED);
		perform(get(path() + "/messages"), first, null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CHAT_NOTICE_REQUIRED"));
		var repeated = notices.enter(room.getPublicId(), firstId, UUID.randomUUID());
		assertThat(repeated.notices().getFirst().noticeId())
			.isEqualTo(firstNotice.noticeId());
		UUID ack = UUID.randomUUID();
		notices.ack(room.getPublicId(), firstNotice.noticeId(), firstId, ack);
		Instant at = jdbc.queryForObject("select first_notice_acked_at from chat_members "
			+ "where chat_room_id=? and user_id=?", Timestamp.class, room.getId(), firstId).toInstant();
		notices.ack(room.getPublicId(), firstNotice.noticeId(), firstId, ack);
		assertThat(jdbc.queryForObject("select first_notice_acked_at from chat_members "
			+ "where chat_room_id=? and user_id=?", Timestamp.class, room.getId(), firstId).toInstant())
			.isEqualTo(at);
		assertThat(notices.enter(room.getPublicId(), firstId, UUID.randomUUID()).notices()).isEmpty();
		assertThat(chats.room(room.getPublicId(), firstId).canSend()).isTrue();
		var other = notices.enter(room.getPublicId(), secondId, UUID.randomUUID());
		assertThat(other.notices().getFirst().expiresAt())
			.isEqualTo(firstNotice.expiresAt());
		assertThat(chats.room(room.getPublicId(), secondId).canSend()).isFalse();
	}

	@Test
	void noticeHttpEndpointsPreserveOwnershipCacheAndAckContract() throws Exception {
		String json = perform(post(path() + "/entries").header("Idempotency-Key", UUID.randomUUID()),
			first, null).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.notices[0].acknowledgedAt").isEmpty())
			.andReturn().getResponse().getContentAsString();
		String id = jsonMapper.readTree(json).path("notices").get(0).path("noticeId").asString();
		perform(put(path() + "/notices/" + id + "/ack").header("Idempotency-Key", UUID.randomUUID()),
			second, null).andExpect(status().isNotFound());
		perform(put(path() + "/notices/" + id + "/ack").header("Idempotency-Key", UUID.randomUUID()),
			first, null).andExpect(status().isOk()).andExpect(jsonPath("$.serverTime").isString());
		perform(get(path() + "/notices"), first, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void installationHttpRegistrationBindsJwtAndDoesNotReturnTheToken() throws Exception {
		UUID id = UUID.randomUUID();
		perform(put("/api/v1/push-installations"), first,
			toJson(Map.of("installationId", id, "platform", "ANDROID", "token", "synthetic-token")))
			.andExpect(status().isOk()).andExpect(jsonPath("$.bindingId").isString())
			.andExpect(jsonPath("$.token").doesNotExist());
		perform(delete("/api/v1/push-installations/" + id), second, null).andExpect(status().isNotFound());
		perform(delete("/api/v1/push-installations/" + id), first, null).andExpect(status().isNoContent());
	}

	@Test
	void duplicateConcurrentEntriesAndAcksCreateOneLogicalNotice() throws Exception {
		var entries = runConcurrently(
			() -> notices.enter(room.getPublicId(), firstId, UUID.randomUUID()),
			() -> notices.enter(room.getPublicId(), firstId, UUID.randomUUID()));
		UUID id = entries.getFirst().notices().getFirst().noticeId();
		assertThat(entries.getLast().notices().getFirst().noticeId()).isEqualTo(id);
		runConcurrently(() -> notices.ack(room.getPublicId(), id, firstId, UUID.randomUUID()),
			() -> notices.ack(room.getPublicId(), id, firstId, UUID.randomUUID()));
		assertThat(jdbc.queryForObject("select count(*) from background_jobs where business_key=?", Long.class,
			ChatNoticeJob.key(room.getId(), firstId, "FIRST_ENTRY"))).isEqualTo(1);
	}

	@Test
	void ackRejectsAnotherUsersNoticeAndReusedOperationKey() {
		UUID entryKey = UUID.randomUUID();
		UUID id = notices.enter(room.getPublicId(), firstId, entryKey).notices().getFirst().noticeId();
		assertCode(() -> notices.ack(room.getPublicId(), id, secondId, UUID.randomUUID()),
			ErrorCode.RESOURCE_NOT_FOUND);
		assertCode(() -> notices.ack(room.getPublicId(), id, firstId, entryKey),
			ErrorCode.IDEMPOTENCY_CONFLICT);
		UUID early = endJob(firstId).getPublicId();
		assertCode(() -> notices.ack(room.getPublicId(), early, firstId, UUID.randomUUID()),
			ErrorCode.NOTICE_STATE_CONFLICT);
	}

	@Test
	void lateFirstEntryKeepsTwoNoticesAndShowsActualRemainingTime() {
		given(testClock.instant()).willReturn(room.getExpiresAt().minusSeconds(3601));
		assertThat(notices.list(room.getPublicId(), firstId).items()).isEmpty();
		given(testClock.instant()).willReturn(room.getExpiresAt().minusSeconds(3539));
		var items = notices.enter(room.getPublicId(), firstId, UUID.randomUUID()).notices();
		assertThat(items).hasSize(2);
		assertThat(items.getLast().type()).isEqualTo("ONE_HOUR_BEFORE_END");
		assertThat(items.getLast().scheduledAt()).isEqualTo(room.getExpiresAt().minusSeconds(3600));
		assertThat(items.getLast().text()).contains("59").doesNotContain("1시간 뒤");
	}

	@Test
	void expiryBlocksNewNoticesButAllowsOwnedAckWithoutRestoringMessages() {
		UUID id = notices.enter(room.getPublicId(), firstId, UUID.randomUUID()).notices().getFirst().noticeId();
		given(testClock.instant()).willReturn(room.getExpiresAt());
		assertCode(() -> notices.enter(room.getPublicId(), firstId, UUID.randomUUID()), ErrorCode.CHAT_CLOSED);
		assertCode(() -> notices.list(room.getPublicId(), firstId), ErrorCode.CHAT_CLOSED);
		notices.ack(room.getPublicId(), id, firstId, UUID.randomUUID());
		assertThat(chats.room(room.getPublicId(), firstId).canRead()).isFalse();
		assertCode(() -> chats.messages(room.getPublicId(), firstId, null, 20), ErrorCode.CHAT_CLOSED);
	}

	@Test
	void scheduledDeliveryIsNotDisplayAckAndDoesNotSendTwice() throws Exception {
		register(first, UUID.randomUUID(), "synthetic-token");
		assertThat(endJob(firstId).getRunAt()).isEqualTo(room.getExpiresAt().minusSeconds(3600));
		given(testClock.instant()).willReturn(room.getExpiresAt().minusSeconds(3600));
		dispatcher.runDue();
		dispatcher.runDue();
		Mockito.verify(gateway).send(eq("synthetic-token"),
			eq(endJob(firstId).getPublicId().toString()),
			anyMap(), eq(room.getExpiresAt()));
		assertThat(jdbc.queryForObject("select end_notice_acked_at from chat_members "
			+ "where chat_room_id=? and user_id=?", Timestamp.class, room.getId(), firstId)).isNull();
		assertThat(notices.list(room.getPublicId(), firstId).items()).hasSize(1);
	}

	@Test
	void logoutSuppressesOnlyThatInstallationsSessionAndKeepsItsStoredConnection() throws Exception {
		UUID installation = UUID.randomUUID();
		register(first, installation, "synthetic-token");
		String socialId = jdbc.queryForObject("select provider_subject from app_users where user_id=?",
			String.class, firstId);
		login("google", socialId);
		logout(first.path("refreshToken").asString()).andExpect(status().isNoContent());
		given(testClock.instant()).willReturn(room.getExpiresAt().minusSeconds(3600));
		dispatcher.runDue();
		Mockito.verify(gateway, Mockito.never()).send(anyString(),
			anyString(), anyMap(), any());
		assertThat(installationRows.findByClientInstallationIdAndUserId(installation, firstId)).isPresent();
	}

	@Test
	void accountSwitchInvalidatesCapturedTargetAndChangesTheClientBinding() throws Exception {
		UUID installation = UUID.randomUUID();
		UUID old = register(first, installation, "old-token");
		given(testClock.instant()).willReturn(room.getExpiresAt().minusSeconds(3600));
		var claim = delivery.claim();
		var target = delivery.targets(claim).getFirst();
		assertCode(() -> register(second, installation, "new-token"), ErrorCode.NOT_FOUND);
		assertThat(installationRows.findByClientInstallationIdAndUserId(installation, firstId)).isPresent();
		installations.remove(firstId, family(first), installation);
		UUID changed = register(second, installation, "new-token");
		assertThat(changed).isNotEqualTo(old);
		assertThat(delivery.validate(claim, target)).isFalse();
		assertThat(installationRows.findByClientInstallationIdAndUserId(installation, firstId)).isEmpty();
	}

	@Test
	void retryKeepsTheNoticeIdAndExpiryPreventsLateDelivery() throws Exception {
		register(first, UUID.randomUUID(), "synthetic-token");
		given(gateway.send(anyString(), anyString(),
			anyMap(), any())).willReturn(PushGateway.Result.RETRY);
		Instant due = room.getExpiresAt().minusSeconds(3600);
		UUID id = endJob(firstId).getPublicId();
		given(testClock.instant()).willReturn(due);
		dispatcher.runDue();
		assertThat(endJob(firstId).getStatus()).isEqualTo("PENDING");
		assertThat(endJob(firstId).getAttempts()).isEqualTo(1);
		assertThat(endJob(firstId).getPublicId()).isEqualTo(id);
		given(testClock.instant()).willReturn(room.getExpiresAt());
		dispatcher.runDue();
		assertThat(endJob(firstId).getStatus()).isEqualTo("CANCELLED");
		Mockito.verify(gateway).send(anyString(),
			anyString(), anyMap(), any());
	}

	@Test
	void transientFailureRetriesTheSameNoticeWithItsOriginalSchedule() throws Exception {
		register(first, UUID.randomUUID(), "synthetic-token");
		given(gateway.send(anyString(), anyString(), anyMap(), any()))
			.willReturn(PushGateway.Result.RETRY, PushGateway.Result.ACCEPTED);
		Instant due = room.getExpiresAt().minusSeconds(3600);
		UUID id = endJob(firstId).getPublicId();
		given(testClock.instant()).willReturn(due);
		dispatcher.runDue();
		given(testClock.instant()).willReturn(due.plusSeconds(31));
		dispatcher.runDue();
		assertThat(endJob(firstId).getStatus()).isEqualTo("SUCCEEDED");
		assertThat(endJob(firstId).getAttempts()).isEqualTo(2);
		assertThat(endJob(firstId).getPublicId()).isEqualTo(id);
		assertThat(notices.list(room.getPublicId(), firstId).items().getFirst().scheduledAt()).isEqualTo(due);
		Mockito.verify(gateway, Mockito.times(2)).send(anyString(), eq(id.toString()), anyMap(), any());
	}

	@Test
	void loginAfterTheOriginalDueTimeDoesNotReplayAFailedOldNotification() throws Exception {
		UUID installation = UUID.randomUUID();
		register(first, installation, "synthetic-token");
		given(gateway.send(anyString(), anyString(), anyMap(), any())).willReturn(PushGateway.Result.RETRY);
		Instant due = room.getExpiresAt().minusSeconds(3600);
		given(testClock.instant()).willReturn(due);
		dispatcher.runDue();
		given(testClock.instant()).willReturn(due.plusSeconds(31));
		logout(first.path("refreshToken").asString()).andExpect(status().isNoContent());
		String subject = jdbc.queryForObject("select provider_subject from app_users where user_id=?",
			String.class, firstId);
		JsonNode loggedIn = login("google", subject);
		register(loggedIn, installation, "new-token");
		dispatcher.runDue();
		Mockito.verify(gateway).send(anyString(), anyString(), anyMap(), any());
		assertThat(notices.list(room.getPublicId(), firstId).items()).hasSize(1);
	}

	@Test
	void reclaimFencesOffTheOldWorkerAndConcurrentClaimsAreDistinct() throws Exception {
		Instant due = room.getExpiresAt().minusSeconds(3600);
		given(testClock.instant()).willReturn(due);
		var claims = runConcurrently(delivery::claim, delivery::claim);
		assertThat(claims.getFirst().jobId()).isNotEqualTo(claims.getLast().jobId());
		given(testClock.instant()).willReturn(due.plusSeconds(31));
		var replacement = delivery.claim();
		var old = claims.stream().filter(c -> c.jobId() == replacement.jobId()).findFirst().orElseThrow();
		delivery.finish(old, false);
		assertThat(jobs.findById(replacement.jobId()).orElseThrow().getStatus()).isEqualTo("RUNNING");
		delivery.finish(replacement, false);
		assertThat(jobs.findById(replacement.jobId()).orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
	}

	@Test
	void registeredTokenIsEncryptedAndInvalidTokenDoesNotAcknowledgeDisplay() throws Exception {
		UUID installation = UUID.randomUUID();
		register(first, installation, "synthetic-private-token");
		assertThat(installationRows.findByClientInstallationIdAndUserId(installation, firstId).orElseThrow()
			.getTokenCiphertext()).doesNotContain("synthetic-private-token");
		given(gateway.send(anyString(), anyString(),
			anyMap(), any()))
			.willReturn(PushGateway.Result.INVALID_TOKEN);
		given(testClock.instant()).willReturn(room.getExpiresAt().minusSeconds(3600));
		dispatcher.runDue();
		assertThat(installationRows.findByClientInstallationIdAndUserId(installation, firstId)).isEmpty();
		assertThat(endJob(firstId).getResultJson().values()).containsOnly("INVALID_TOKEN");
		assertThat(notices.list(room.getPublicId(), firstId).items()).hasSize(1);
	}

	@Test
	void tokenRotationKeepsBindingButRejectsOldCandidatesAndStaleInvalidTokenResults() throws Exception {
		UUID installation = UUID.randomUUID();
		UUID original = register(first, installation, "old-token");
		Instant linked = installationRows.findByClientInstallationIdAndUserId(installation, firstId)
			.orElseThrow().getLinkedAt();
		given(testClock.instant()).willReturn(room.getExpiresAt().minusSeconds(3600));
		var claim = delivery.claim();
		var target = delivery.targets(claim).getFirst();

		assertThat(register(first, installation, "new-token")).isEqualTo(original);
		assertThat(installationRows.findByClientInstallationIdAndUserId(installation, firstId)
			.orElseThrow().getLinkedAt()).isEqualTo(linked);
		assertThat(delivery.validate(claim, target)).isFalse();
		assertThat(delivery.record(claim, target, PushGateway.Result.INVALID_TOKEN)).isTrue();
		assertThat(installationRows.findByClientInstallationIdAndUserId(installation, firstId)).isPresent();
		assertThat(endJob(firstId).alreadyDelivered(original.toString())).isFalse();
		delivery.finish(claim, true);
		given(testClock.instant()).willReturn(room.getExpiresAt().minusSeconds(3569));
		dispatcher.runDue();
		Mockito.verify(gateway).send(eq("new-token"), anyString(), anyMap(), any());
	}

	@Test
	void sameAccountNewLoginGetsNewBindingAndOldRefreshProofCannotReleaseIt() throws Exception {
		UUID installation = UUID.randomUUID();
		UUID original = register(first, installation, "old-token");
		String subject = jdbc.queryForObject("select provider_subject from app_users where user_id=?",
			String.class, firstId);
		JsonNode signedIn = login("google", subject);
		UUID replacement = register(signedIn, installation, "new-token");
		assertThat(replacement).isNotEqualTo(original);
		mockMvc.perform(post("/api/v1/auth/logout").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content(toJson(Map.of("refreshToken", first.path("refreshToken").asString(),
				"releaseInstallationId", installation))))
			.andExpect(status().isNoContent());
		assertThat(installationRows.findByClientInstallationIdAndUserId(installation, firstId).orElseThrow()
			.getBindingId()).isEqualTo(replacement);
	}

	@Test
	void expiredOrConsumedRefreshFamilyCannotChangeOrRemoveTheInstallation() throws Exception {
		UUID installation = UUID.randomUUID();
		UUID original = register(first, installation, "original-token");
		UUID session = family(first);
		jdbc.update("update refresh_tokens set expires_at=? where family_id=?",
			Timestamp.from(testClock.instant().minusSeconds(1)), session);
		assertCode(() -> register(first, installation, "replacement"), ErrorCode.SESSION_REVOKED);
		assertCode(() -> installations.remove(firstId, session, installation), ErrorCode.SESSION_REVOKED);

		jdbc.update("update refresh_tokens set expires_at=?,used_at=? where family_id=?",
			Timestamp.from(testClock.instant().plusSeconds(3600)), Timestamp.from(testClock.instant()), session);
		assertCode(() -> register(first, installation, "replacement"), ErrorCode.SESSION_REVOKED);
		assertThat(installationRows.findByClientInstallationIdAndUserId(installation, firstId).orElseThrow()
			.getBindingId()).isEqualTo(original);
	}

	private UUID register(JsonNode tokens, UUID installation, String token) throws Exception {
		return installations.register(userIdOf(tokens), family(tokens),
			new RegisterPushRequest(installation, PushPlatform.ANDROID, token)).bindingId();
	}

	private UUID family(JsonNode tokens) throws Exception {
		String jwt = tokens.path("accessToken").asString().split("\\.")[1];
		return UUID.fromString(jsonMapper.readTree(java.util.Base64.getUrlDecoder().decode(jwt))
			.path("sid").asString());
	}

	private ChatNoticeJob endJob(long userId) {
		return jobs.findByBusinessKey(ChatNoticeJob.key(room.getId(), userId, "END_NOTICE")).orElseThrow();
	}

	private String path() {
		return "/api/v1/chat-rooms/" + room.getPublicId();
	}

	private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
	}
}
