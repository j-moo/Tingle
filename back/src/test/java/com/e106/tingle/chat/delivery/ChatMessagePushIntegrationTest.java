package com.e106.tingle.chat.delivery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.chat.ChatMessageRepository;
import com.e106.tingle.chat.ChatRoom;
import com.e106.tingle.chat.ChatRoomRepository;
import com.e106.tingle.chat.ChatService;
import com.e106.tingle.chat.deletion.restore.RestoreServingGateFilter;
import com.e106.tingle.chat.dto.MessageView;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.ProposalService;
import com.e106.tingle.push.PushGateway;

import tools.jackson.databind.JsonNode;

@SpringBootTest(properties = {
	"app.push.token-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class ChatMessagePushIntegrationTest extends IntegrationTestSupport {
	@Autowired
	private ChatService chats;
	@Autowired
	private ChatRoomRepository rooms;
	@Autowired
	private ChatMessageRepository messages;
	@Autowired
	private ProposalService proposals;
	@Autowired
	private ProposalRepository proposalRows;
	@Autowired
	private MatchingParticipantRepository participantRows;
	@Autowired
	private ChatMessagePushDispatcher dispatcher;
	@Autowired
	private ChatMessagePushStore store;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@MockitoBean
	private PushGateway gateway;
	@MockitoSpyBean
	private RestoreServingGateFilter servingGate;
	private JsonNode first;
	private JsonNode second;
	private String secondSocialId;
	private long firstId;
	private long secondId;
	private ChatRoom room;
	private UUID installationId;

	@BeforeEach
	void prepareChatAndInstallation() throws Exception {
		given(gateway.ready()).willReturn(true);
		given(gateway.send(anyString(), anyString(), anyMap(), any())).willReturn(PushGateway.Result.ACCEPTED);
		first = login("google", newSocialId());
		secondSocialId = newSocialId();
		second = login("google", secondSocialId);
		completeOnboarding(first);
		completeOnboarding(second);
		firstId = userIdOf(first);
		secondId = userIdOf(second);
		MatchProposal proposal = proposalRows.saveAndFlush(MatchProposal.proposed(firstId, secondId, clock.instant()));
		participantRows.saveAllAndFlush(List.of(new MatchingParticipant(proposal.getId(), firstId),
			new MatchingParticipant(proposal.getId(), secondId)));
		proposals.decide(proposal.getId(), firstId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposal.getId(), firstId).profileVersion());
		proposals.decide(proposal.getId(), secondId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposal.getId(), secondId).profileVersion());
		room = rooms.findBySourceProposalId(proposal.getId()).orElseThrow();
		installationId = UUID.randomUUID();
		register(second, "synthetic-recipient-token");
	}

	@AfterEach
	void isolatePendingNotifications() {
		if (room != null) {
			jdbc.update("update chat_message set push_state='{}'::jsonb where chat_id=?", room.getId());
		}
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void commitsBeforeSendingAndRetriesOfTheSameMessageDoNotCreateAnotherNotification() {
		UUID clientId = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		MessageView sent = chats.send(room.getPublicId(), firstId, clientId, key, "private message body");
		assertThat(chats.send(room.getPublicId(), firstId, clientId, key, "private message body").messageId())
			.isEqualTo(sent.messageId());
		then(gateway).should(never()).send(anyString(), anyString(), anyMap(), any());
		assertThat(dispatcher.runDue()).isEqualTo(1);
		assertThat(dispatcher.runDue()).isZero();
		ArgumentCaptor<Map<String, String>> payload = ArgumentCaptor.forClass((Class)Map.class);
		then(gateway).should().send(eq("synthetic-recipient-token"), eq("chat-message:" + sent.messageId()),
			payload.capture(), any());
		assertThat(payload.getValue()).containsEntry("type", "CHAT_MESSAGE")
			.containsEntry("recipientUserId", Long.toString(secondId))
			.containsEntry("chatRoomId", room.getPublicId().toString())
			.containsEntry("messageId", sent.messageId().toString());
		// 알림에는 보낸 사람 닉네임 · 메시지 미리보기를 싣되, 작업 기록(push_state)에는 본문을 남기지 않는다
		assertThat(payload.getValue()).containsEntry("body", "private message body").containsKey("title")
			.doesNotContainKeys("text", "partnerProfile", "token");
		assertThat(state(sent)).doesNotContain("synthetic-recipient-token", "private message body");
		assertThat(jdbc.queryForObject("select read_state from chat_participant where chat_id=? and user_id=?",
			String.class, room.getId(), secondId)).isEqualTo("{}");
	}

	@Test
	void rolledBackMessageNeverBecomesClaimable() {
		AtomicLong messageId = new AtomicLong();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			messageId.set(send().messageId());
			status.setRollbackOnly();
		});
		assertThat(messages.findById(messageId.get())).isEmpty();
		assertThat(dispatcher.runDue()).isZero();
		then(gateway).should(never()).send(anyString(), anyString(), anyMap(), any());
	}

	@Test
	void providerTimeoutDoesNotUndoTheMessageAndOnlyRetriesWhenDue() {
		given(gateway.send(anyString(), anyString(), anyMap(), any()))
			.willThrow(new IllegalStateException("synthetic timeout")).willReturn(PushGateway.Result.ACCEPTED);
		MessageView sent = send();
		assertThat(dispatcher.runDue()).isEqualTo(1);
		assertThat(messages.findById(sent.messageId())).isPresent();
		assertThat(jsonMapper.readTree(state(sent)).path("status").asString()).isEqualTo("PENDING");
		assertThat(dispatcher.runDue()).isZero();
		timeShift.set(Duration.ofSeconds(31));
		assertThat(dispatcher.runDue()).isEqualTo(1);
		assertThat(jsonMapper.readTree(state(sent)).path("status").asString()).isEqualTo("SUCCEEDED");
		then(gateway).should(times(2)).send(anyString(), eq("chat-message:" + sent.messageId()), anyMap(), any());
	}

	@Test
	void invalidTokenIsRemovedAndPermanentFailureIsNotRetried() {
		given(gateway.send(anyString(), anyString(), anyMap(), any())).willReturn(PushGateway.Result.INVALID_TOKEN);
		MessageView sent = send();
		dispatcher.runDue();
		assertThat(jdbc.queryForObject("select count(*) from push_installation where client_installation_id=?",
			Long.class, installationId)).isZero();
		assertThat(state(sent)).contains("INVALID_TOKEN");
		timeShift.set(Duration.ofMinutes(1));
		assertThat(dispatcher.runDue()).isZero();
	}

	@Test
	void logoutThenNewLoginCannotBindOldNotificationsToTheNewGeneration() throws Exception {
		MessageView sent = send();
		logout(second.path("refreshToken").asString()).andExpect(status().isNoContent());
		second = login("google", secondSocialId);
		register(second, "synthetic-new-login-token");
		dispatcher.runDue();
		then(gateway).should(never()).send(anyString(), anyString(), anyMap(), any());
		assertThat(state(sent)).contains("SUCCEEDED");
	}

	@Test
	void accountSwitchRequiresReleaseAndNeverTransfersQueuedNotifications() throws Exception {
		MessageView sent = send();
		JsonNode different = login("google", newSocialId());
		completeOnboarding(different);
		perform(put("/api/push-installations"), different, toJson(Map.of("installationId", installationId,
			"platform", "ANDROID", "token", "synthetic-switched-token"))).andExpect(status().isNotFound());
		logout(second.path("refreshToken").asString()).andExpect(status().isNoContent());
		register(different, "synthetic-switched-token");
		assertThat(jdbc.queryForObject("select user_id from push_installation where client_installation_id=?",
			Long.class, installationId)).isEqualTo(userIdOf(different));
		dispatcher.runDue();
		then(gateway).should(never()).send(anyString(), anyString(), anyMap(), any());
		assertThat(state(sent)).contains("SUCCEEDED");
	}

	@Test
	void withdrawnConsentCancelsPendingNotifications() {
		MessageView sent = send();
		jdbc.update("update user_consents set agreed=false where user_id=? and consent_type='AI_RISK_DETECTION'",
			secondId);
		dispatcher.runDue();
		then(gateway).should(never()).send(anyString(), anyString(), anyMap(), any());
		assertThat(state(sent)).contains("CANCELLED");
	}

	@Test
	void expiryAtTheExactDeadlineAndBlockedRoomDoNotSend() {
		MessageView sent = send();
		jdbc.update("update chat set expires_at=? where id=?", java.sql.Timestamp.from(clock.instant()), room.getId());
		dispatcher.runDue();
		then(gateway).should(never()).send(anyString(), anyString(), anyMap(), any());
		assertThat(state(sent)).contains("CANCELLED");
	}

	@Test
	void blockIsRecheckedImmediatelyBeforeProviderInvocation() {
		MessageView sent = send();
		ChatMessagePushStore.Claim claim = store.claim();
		ChatMessagePushStore.Target target = store.targets(claim).getFirst();
		jdbc.update("update matching_participant set blocked_at=? where matching_id=? and user_id=?",
			java.sql.Timestamp.from(clock.instant()), room.getSourceProposalId(), secondId);
		assertThat(store.resolve(claim, target)).isEmpty();
		store.finish(claim);
		assertThat(state(sent)).contains("CANCELLED");
		then(gateway).should(never()).send(anyString(), anyString(), anyMap(), any());
	}

	@Test
	void tokenRotationDuringProviderFailureDoesNotInvalidateTheReplacement() throws Exception {
		MessageView sent = send();
		ChatMessagePushStore.Claim claim = store.claim();
		ChatMessagePushStore.Target target = store.targets(claim).getFirst();
		register(second, "synthetic-replacement-token");
		store.record(claim, target, PushGateway.Result.INVALID_TOKEN);
		store.finish(claim);
		assertThat(jdbc.queryForObject("select count(*) from push_installation where client_installation_id=?",
			Long.class, installationId)).isEqualTo(1);
		assertThat(state(sent)).contains("INVALID_TOKEN");
	}

	@Test
	void retriesAreBounded() {
		given(gateway.send(anyString(), anyString(), anyMap(), any())).willReturn(PushGateway.Result.RETRY);
		MessageView sent = send();
		for (int seconds : List.of(0, 31, 92, 213, 454)) {
			timeShift.set(Duration.ofSeconds(seconds));
			assertThat(dispatcher.runDue()).isEqualTo(1);
		}
		assertThat(jsonMapper.readTree(state(sent)).path("status").asString()).isEqualTo("FAILED");
		assertThat(dispatcher.runDue()).isZero();
		then(gateway).should(times(5)).send(anyString(), eq("chat-message:" + sent.messageId()), anyMap(), any());
	}

	@Test
	void restoreGateBlocksAlreadyClaimedTargetsWithoutCancellingThem() {
		MessageView sent = send();
		ChatMessagePushStore.Claim claim = store.claim();
		ChatMessagePushStore.Target target = store.targets(claim).getFirst();
		willReturn(false).given(servingGate).servingAllowed();
		assertThat(store.resolve(claim, target)).isEmpty();
		store.finish(claim);
		assertThat(jsonMapper.readTree(state(sent)).path("status").asString()).isEqualTo("RUNNING");
		assertThat(dispatcher.runDue()).isZero();
		then(gateway).should(never()).send(anyString(), anyString(), anyMap(), any());
	}

	@Test
	void oldClaimCannotRecordAfterLeaseWasReclaimed() {
		MessageView sent = send();
		ChatMessagePushStore.Claim old = store.claim();
		ChatMessagePushStore.Target target = store.targets(old).getFirst();
		timeShift.set(Duration.ofSeconds(31));
		ChatMessagePushStore.Claim current = store.claim();
		assertThat(current.attempt()).isEqualTo(old.attempt() + 1);
		store.record(old, target, PushGateway.Result.ACCEPTED);
		assertThat(jsonMapper.readTree(state(sent)).path("results").isEmpty()).isTrue();
		assertThat(store.resolve(old, target)).isEmpty();
		store.record(current, store.targets(current).getFirst(), PushGateway.Result.ACCEPTED);
		store.finish(current);
		assertThat(jsonMapper.readTree(state(sent)).path("status").asString()).isEqualTo("SUCCEEDED");
	}

	@Test
	void noInstallationAndLegacyMessagesDoNotProduceNotifications() {
		jdbc.update("delete from push_installation where client_installation_id=?", installationId);
		MessageView sent = send();
		assertThat(jsonMapper.readTree(state(sent)).path("candidates").isEmpty()).isTrue();
		jdbc.update("update chat_message set push_state='{}'::jsonb where id=?", sent.messageId());
		assertThat(dispatcher.runDue()).isZero();
		then(gateway).should(never()).send(anyString(), anyString(), anyMap(), any());
	}

	private MessageView send() {
		return chats.send(room.getPublicId(), firstId, UUID.randomUUID(), UUID.randomUUID(), "hello");
	}

	private String state(MessageView sent) {
		return jdbc.queryForObject("select push_state from chat_message where id=?", String.class, sent.messageId());
	}

	private void register(JsonNode tokens, String token) throws Exception {
		perform(put("/api/push-installations"), tokens, toJson(Map.of("installationId", installationId,
			"platform", "ANDROID", "token", token))).andExpect(status().isOk());
	}
}
