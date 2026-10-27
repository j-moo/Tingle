package com.e106.tingle.chat;

import static org.assertj.core.api.Assertions.*;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.chat.dto.MessageView;
import com.e106.tingle.chat.notice.ChatNoticeService;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.PairRelation;
import com.e106.tingle.proposal.PairRelationRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.ProposalService;

import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class ChatIntegrationTest extends IntegrationTestSupport {
	@Autowired
	private ChatService chats;
	@Autowired
	private ChatNoticeService notices;
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
			+ "returning encounter_id", Long.class, pair.getId(), UUID.randomUUID().toString(), Timestamp.from(now),
			Timestamp.from(now.plus(Duration.ofDays(7))), Timestamp.from(now.plus(Duration.ofDays(7))));
		MatchProposal proposal = proposalRows.saveAndFlush(new MatchProposal(pair.getId(), encounter, now));
		proposalId = proposal.getId();
		proposals.decide(proposalId, firstUserId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposalId, firstUserId).profileVersion());
		assertThat(rooms.findBySourceProposalId(proposalId)).isEmpty();
		proposals.decide(proposalId, secondUserId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposalId, secondUserId).profileVersion());
		ChatRoom saved = rooms.findBySourceProposalId(proposalId).orElseThrow();
		for (long userId : List.of(firstUserId, secondUserId)) {
			if (chats.available(saved, userId)) {
				ackFirst(saved, userId);
			}
		}
		return saved;
	}

	@Test
	void mutualAcceptancePersistsOneRoomAndKeepsItsOriginalLifetime() throws Exception {
		var result = proposals.get(proposalId, firstId);
		assertThat(result.chatAvailable()).isTrue();
		assertThat(result.chatRoomId()).isEqualTo(room.getPublicId().toString());
		proposals.decide(proposalId, firstId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(), "stale-retry");
		ChatRoom saved = rooms.findBySourceProposalId(proposalId).orElseThrow();
		assertThat(saved.getPublicId()).isEqualTo(room.getPublicId());
		assertThat(saved.getCreatedAt()).isEqualTo(room.getCreatedAt());
		assertThat(saved.getExpiresAt()).isEqualTo(room.getCreatedAt().plus(Duration.ofHours(168)));
		assertThat(proposals.list(firstId, null, 100).items()).isEmpty();
		perform(get("/api/v1/chat-rooms"), first, null).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.items[0].chatRoomId").value(room.getPublicId().toString()));
	}

	@Test
	void storesAndReturnsOnlyTheMaskedWholeBodyAndKeepsDuplicateIdentity() throws Exception {
		UUID client = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		String text = "x.co ".repeat(200);
		MessageView sent = chats.send(room.getPublicId(), firstId, client, key, text);
		assertThat(sent.text()).hasSizeGreaterThan(1000).doesNotContain("x.co");
		MessageView retry = chats.send(room.getPublicId(), firstId, client, key, text);
		assertThat(retry).isEqualTo(sent);
		assertThat(jdbc.queryForObject("select count(*) from chat_messages where chat_room_id=?", Long.class,
			room.getId())).isEqualTo(1);
		assertThat(chats.messages(room.getPublicId(), secondId, null, 20).items().getFirst().sender())
			.isEqualTo("PARTNER");
		assertCode(() -> chats.send(room.getPublicId(), firstId, client, key, "different"),
			ErrorCode.IDEMPOTENCY_CONFLICT);
		assertCode(() -> chats.send(room.getPublicId(), firstId, UUID.randomUUID(), key, text),
			ErrorCode.IDEMPOTENCY_CONFLICT);
	}

	@Test
	void rejectsInvalidTextAttachmentsAndMissingKeysAtTheApi() throws Exception {
		for (String text : List.of(" ", "가".repeat(1001))) {
			perform(post(path() + "/messages").header("Idempotency-Key", UUID.randomUUID()), first,
				toJson(Map.of("clientMessageId", UUID.randomUUID(), "text", text))).andExpect(status().isBadRequest());
		}
		perform(post(path() + "/messages").header("Idempotency-Key", UUID.randomUUID()), first,
			toJson(Map.of("clientMessageId", UUID.randomUUID(), "text", "안녕", "attachment", "file")))
			.andExpect(status().isBadRequest());
		perform(post(path() + "/messages"), first,
			toJson(Map.of("clientMessageId", UUID.randomUUID(), "text", "안녕"))).andExpect(status().isBadRequest());
		perform(post(path() + "/messages").header("Idempotency-Key", UUID.randomUUID()), first,
			toJson(Map.of("clientMessageId", UUID.randomUUID(), "text", "안녕\r\nhttps://example.com")))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.text").value("안녕\n[웹 주소 차단]"));
	}

	@Test
	void concurrentDuplicateSendsPersistOneMessageAndSequence() throws Exception {
		UUID client = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		List<MessageView> results = runConcurrently(
			() -> chats.send(room.getPublicId(), firstId, client, key, "hello"),
			() -> chats.send(room.getPublicId(), firstId, client, key, "hello"));
		assertThat(results.getFirst()).isEqualTo(results.getLast());
		assertThat(rooms.findById(room.getId()).orElseThrow().getLastSequence()).isEqualTo(1);
	}

	@Test
	void readsOnlyExplicitlyDisplayedMessagesWithoutFillingGaps() throws Exception {
		MessageView older = send("older");
		MessageView newer = send("newer");
		perform(get(path() + "/messages"), second, null).andExpect(status().isOk());
		assertThat(chats.messages(room.getPublicId(), firstId, null, 20).items())
			.allMatch(message -> !message.readByPartner());
		perform(post(path() + "/reads"), second, toJson(Map.of("messageIds", List.of(newer.messageId()))))
			.andExpect(status().isOk());
		var page = chats.messages(room.getPublicId(), firstId, null, 20);
		assertThat(page.items().getFirst().readByPartner()).isTrue();
		assertThat(page.items().getLast().messageId()).isEqualTo(older.messageId());
		assertThat(page.items().getLast().readByPartner()).isFalse();
		Instant recorded = jdbc.queryForObject("select read_at from chat_message_reads r join chat_messages m "
			+ "on m.message_id=r.message_id where m.public_id=?", Timestamp.class, newer.messageId()).toInstant();
		chats.read(room.getPublicId(), secondId, List.of(newer.messageId(), newer.messageId()));
		assertThat(jdbc.queryForObject("select read_at from chat_message_reads r join chat_messages m "
			+ "on m.message_id=r.message_id where m.public_id=?", Timestamp.class, newer.messageId()).toInstant())
			.isEqualTo(recorded);
	}

	@Test
	void invalidReadBatchHasNoPartialSideEffectsAndOwnMessagesCannotBeAcknowledged() throws Exception {
		MessageView message = send("hello");
		assertCode(() -> chats.read(room.getPublicId(), secondId, List.of(message.messageId(), UUID.randomUUID())),
			ErrorCode.RESOURCE_NOT_FOUND);
		assertCode(() -> chats.read(room.getPublicId(), firstId, List.of(message.messageId())),
			ErrorCode.INVALID_REQUEST);
		assertThat(chats.messages(room.getPublicId(), firstId, null, 20).items().getFirst().readByPartner()).isFalse();
		List<?> results = runConcurrently(
			() -> chats.read(room.getPublicId(), secondId, List.of(message.messageId())),
			() -> chats.read(room.getPublicId(), secondId, List.of(message.messageId())));
		assertThat(results).hasSize(2);
		assertThat(jdbc.queryForObject("select count(*) from chat_message_reads r join chat_messages m "
			+ "on m.message_id=r.message_id where m.public_id=?", Long.class, message.messageId())).isEqualTo(1);
	}

	@Test
	void nonParticipantsCannotReadSendOrAcknowledgeAndBlocksTakeEffectImmediately() throws Exception {
		JsonNode outsider = login("google", newSocialId());
		completeOnboarding(outsider);
		for (String suffix : List.of("", "/messages")) {
			perform(get(path() + suffix), outsider, null).andExpect(status().isNotFound());
		}
		perform(post(path() + "/messages").header("Idempotency-Key", UUID.randomUUID()), outsider,
			toJson(Map.of("clientMessageId", UUID.randomUUID(), "text", "hello"))).andExpect(status().isNotFound());
		perform(post(path() + "/reads"), outsider, toJson(Map.of("messageIds", List.of(UUID.randomUUID()))))
			.andExpect(status().isNotFound());
		jdbc.update("insert into user_blocks(blocker_id,blocked_id,created_at) values (?,?,?)", firstId, secondId,
			Timestamp.from(testClock.instant()));
		perform(get(path() + "/messages"), first, null).andExpect(status().isGone());
		assertThat(chats.list(firstId, null, 20).items()).isEmpty();
	}

	@Test
	void deadlineBlocksReadsSendsAndLateReceiptsWithoutExtendingTheRoom() {
		MessageView message = send("before expiry");
		given(testClock.instant()).willReturn(room.getExpiresAt());
		assertCode(() -> chats.messages(room.getPublicId(), firstId, null, 20), ErrorCode.CHAT_CLOSED);
		assertCode(() -> send("late"), ErrorCode.CHAT_CLOSED);
		assertCode(() -> chats.read(room.getPublicId(), secondId, List.of(message.messageId())), ErrorCode.CHAT_CLOSED);
		assertThat(chats.list(firstId, null, 20).items()).isEmpty();
		assertThat(chats.room(room.getPublicId(), firstId).canRead()).isFalse();
		assertThat(rooms.findById(room.getId()).orElseThrow().getExpiresAt()).isEqualTo(room.getExpiresAt());
	}

	@Test
	void withdrawalBlocksOldRoomForBothEvenAfterReconsentButDoesNotChangeProposalAcceptance() {
		Instant withdrawal = room.getCreatedAt().plusSeconds(1);
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (?,'AI_RISK_DETECTION',?,false,?)", firstId, AI_RISK_VERSION, Timestamp.from(withdrawal));
		assertCode(() -> chats.messages(room.getPublicId(), firstId, null, 20), ErrorCode.CHAT_CLOSED);
		assertCode(() -> chats.messages(room.getPublicId(), secondId, null, 20), ErrorCode.CHAT_CLOSED);
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (?,'AI_RISK_DETECTION',?,true,?)", firstId, AI_RISK_VERSION,
			Timestamp.from(withdrawal.plusSeconds(1)));
		assertCode(() -> chats.messages(room.getPublicId(), firstId, null, 20), ErrorCode.CHAT_CLOSED);
		assertThat(proposals.get(proposalId, firstId).status()).isEqualTo("ACCEPTED");
		assertThat(proposals.get(proposalId, firstId).chatAvailable()).isFalse();
	}

	@Test
	void withdrawalStillAllowsANewRoomButRequiresReconsentForTheWithdrawerToUseIt() throws Exception {
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (?,'AI_RISK_DETECTION',?,false,?)", firstId, AI_RISK_VERSION,
			Timestamp.from(testClock.instant()));
		JsonNode third = login("google", newSocialId());
		completeOnboarding(third);
		ChatRoom next = createRoom(firstId, userIdOf(third));
		assertCode(() -> chats.messages(next.getPublicId(), firstId, null, 20), ErrorCode.AI_RISK_CONSENT_REQUIRED);
		assertThat(chats.room(next.getPublicId(), userIdOf(third)).canSend()).isTrue();
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (?,'AI_RISK_DETECTION',?,true,?)", firstId, AI_RISK_VERSION,
			Timestamp.from(testClock.instant()));
		ackFirst(next, firstId);
		assertThat(chats.room(next.getPublicId(), firstId).canSend()).isTrue();
		assertCode(() -> chats.messages(room.getPublicId(), firstId, null, 20), ErrorCode.CHAT_CLOSED);
	}

	@Test
	void messagePaginationKeepsOrderAndDoesNotAcceptAnotherRoomCursor() {
		MessageView firstMessage = send("1");
		send("2");
		var page = chats.messages(room.getPublicId(), firstId, null, 1);
		assertThat(page.items().getFirst().sequence()).isEqualTo(2);
		assertThat(chats.messages(room.getPublicId(), firstId, page.nextCursor(), 1).items().getFirst().messageId())
			.isEqualTo(firstMessage.messageId());
		String foreign = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
			("m|" + UUID.randomUUID() + "|2").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertCode(() -> chats.messages(room.getPublicId(), firstId, foreign, 1), ErrorCode.INVALID_REQUEST);
	}

	private void ackFirst(ChatRoom target, long userId) {
		var entry = notices.enter(target.getPublicId(), userId, UUID.randomUUID());
		for (var notice : entry.notices()) {
			notices.ack(target.getPublicId(), notice.noticeId(), userId, UUID.randomUUID());
		}
	}

	private MessageView send(String text) {
		return chats.send(room.getPublicId(), firstId, UUID.randomUUID(), UUID.randomUUID(), text);
	}

	private String path() {
		return "/api/v1/chat-rooms/" + room.getPublicId();
	}

	private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
	}
}
