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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.chat.dto.MessageView;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.ProposalService;

import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class ChatIntegrationTest extends IntegrationTestSupport {
	@MockitoSpyBean
	private ChatService chats;
	@Autowired
	private ChatRoomRepository rooms;
	@Autowired
	private ChatMemberRepository chatMembers;
	@Autowired
	private ProposalService proposals;
	@Autowired
	private ProposalRepository proposalRows;
	@Autowired
	private MatchingParticipantRepository participantRows;
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
		Instant now = testClock.instant();
		MatchProposal proposal = proposalRows.saveAndFlush(MatchProposal.proposed(firstUserId, secondUserId, now));
		participantRows.saveAllAndFlush(List.of(new MatchingParticipant(proposal.getId(), firstUserId),
			new MatchingParticipant(proposal.getId(), secondUserId)));
		proposalId = proposal.getId();
		proposals.decide(proposalId, firstUserId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposalId, firstUserId).profileVersion());
		assertThat(rooms.findBySourceProposalId(proposalId)).isEmpty();
		proposals.decide(proposalId, secondUserId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposalId, secondUserId).profileVersion());
		return rooms.findBySourceProposalId(proposalId).orElseThrow();
	}

	@Test
	void mutualAcceptancePersistsOneRoomAndKeepsItsOriginalLifetime() throws Exception {
		var result = proposals.get(proposalId, firstId);
		assertThat(result.chatAvailable()).isTrue();
		assertThat(result.chatRoomId()).isEqualTo(room.getPublicId());
		proposals.decide(proposalId, firstId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(), "stale-retry");
		ChatRoom saved = rooms.findBySourceProposalId(proposalId).orElseThrow();
		assertThat(saved.getPublicId()).isEqualTo(room.getPublicId());
		assertThat(saved.getCreatedAt()).isEqualTo(room.getCreatedAt());
		assertThat(saved.getExpiresAt()).isEqualTo(room.getCreatedAt().plus(Duration.ofHours(168)));
		assertThat(proposals.list(firstId, null, 100).items()).isEmpty();
		perform(get("/api/chat-rooms"), first, null).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.items[0].chatRoomId").value(room.getPublicId()));
	}

	@Test
	void messagesAndReadsWorkWithoutEntriesOrNoticeAcknowledgement() throws Exception {
		List<String> originalNotices = noticeStates();
		assertThat(originalNotices).hasSize(2).allSatisfy(state -> assertThat(state).doesNotContain("FIRST_ENTRY"));
		for (JsonNode user : List.of(first, second)) {
			perform(get("/api/chat-rooms"), user, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].canRead").value(true))
				.andExpect(jsonPath("$.items[0].canSend").value(true));
			perform(get(path()), user, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.canRead").value(true)).andExpect(jsonPath("$.canSend").value(true));
			perform(get(path() + "/messages"), user, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());
		}
		String response = perform(post(path() + "/messages").header("Idempotency-Key", UUID.randomUUID()), first,
			toJson(Map.of("clientMessageId", UUID.randomUUID(), "text", "hello without entry acknowledgement")))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long messageId = jsonMapper.readTree(response).path("messageId").asLong();
		perform(get(path() + "/messages"), second, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].messageId").value(messageId));
		assertThat(jdbc.queryForObject("select read_state from chat_participant where chat_id=? and user_id=?",
			String.class, room.getId(), secondId)).isEqualTo("{}");
		perform(post(path() + "/reads"), second, toJson(Map.of("messageIds", List.of(messageId))))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("select last_read_message_seq from chat_participant "
			+ "where chat_id=? and user_id=?", Long.class, room.getId(), secondId)).isEqualTo(1);
		assertThat(noticeStates()).isEqualTo(originalNotices);
	}

	@Test
	void failedDisplayAcknowledgementDoesNotRestrictMessagesOrChangeNoticeState() throws Exception {
		List<String> originalNotices = noticeStates();
		perform(put(path() + "/notices/" + UUID.randomUUID() + "/ack")
			.header("Idempotency-Key", UUID.randomUUID()), first, null).andExpect(status().isNotFound());
		MessageView message = send("hello after failed display acknowledgement");
		perform(get(path() + "/messages"), second, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].messageId").value(message.messageId()));
		perform(post(path() + "/reads"), second, toJson(Map.of("messageIds", List.of(message.messageId()))))
			.andExpect(status().isOk());
		for (long userId : List.of(firstId, secondId)) {
			assertThat(chats.room(room.getPublicId(), userId).canRead()).isTrue();
			assertThat(chats.room(room.getPublicId(), userId).canSend()).isTrue();
		}
		assertThat(noticeStates()).isEqualTo(originalNotices);
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
		assertThat(jdbc.queryForObject("select count(*) from chat_message where chat_id=?", Long.class,
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
		assertThat(jdbc.queryForObject("select read_state from chat_participant where chat_id=? and user_id=?",
			String.class, room.getId(), secondId)).isEqualTo("{}");
		perform(post(path() + "/reads"), second, toJson(Map.of("messageIds", List.of(newer.messageId()))))
			.andExpect(status().isOk());
		perform(get(path() + "/messages"), first, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].readByPartner").doesNotExist());
		var page = chats.messages(room.getPublicId(), firstId, null, 20);
		assertThat(page.items().getLast().messageId()).isEqualTo(older.messageId());
		assertThat(jdbc.queryForObject("select last_read_message_seq from chat_participant "
			+ "where chat_id=? and user_id=?", Long.class, room.getId(), secondId)).isZero();
		Instant recorded = jdbc.queryForObject("select cast(read_state->?->>'readAt' as timestamptz) "
			+ "from chat_participant where chat_id=? and user_id=?", Timestamp.class,
			newer.messageId().toString(), room.getId(), secondId).toInstant();
		chats.read(room.getPublicId(), secondId, List.of(newer.messageId(), newer.messageId()));
		assertThat(jdbc.queryForObject("select cast(read_state->?->>'readAt' as timestamptz) "
			+ "from chat_participant where chat_id=? and user_id=?", Timestamp.class,
			newer.messageId().toString(), room.getId(), secondId).toInstant()).isEqualTo(recorded);
		chats.read(room.getPublicId(), secondId, List.of(older.messageId()));
		assertThat(jdbc.queryForObject("select last_read_message_seq from chat_participant "
			+ "where chat_id=? and user_id=?", Long.class, room.getId(), secondId)).isEqualTo(2);
	}

	@Test
	void invalidReadBatchHasNoPartialSideEffectsAndOwnMessagesCannotBeAcknowledged() throws Exception {
		MessageView message = send("hello");
		assertCode(() -> chats.read(room.getPublicId(), secondId, List.of(message.messageId(), Long.MAX_VALUE)),
			ErrorCode.RESOURCE_NOT_FOUND);
		assertCode(() -> chats.read(room.getPublicId(), firstId, List.of(message.messageId())),
			ErrorCode.INVALID_REQUEST);
		assertThat(jdbc.queryForObject("select read_state from chat_participant where chat_id=? and user_id=?",
			String.class, room.getId(), secondId)).isEqualTo("{}");
		List<?> results = runConcurrently(
			() -> chats.read(room.getPublicId(), secondId, List.of(message.messageId())),
			() -> chats.read(room.getPublicId(), secondId, List.of(message.messageId())));
		assertThat(results).hasSize(2);
		assertThat(jdbc.queryForObject("select count(*) from chat_participant p cross join lateral "
			+ "jsonb_object_keys(p.read_state) r where p.chat_id=? and p.user_id=?", Long.class,
			room.getId(), secondId)).isEqualTo(1);
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
		perform(post(path() + "/reads"), outsider, toJson(Map.of("messageIds", List.of(Long.MAX_VALUE))))
			.andExpect(status().isNotFound());
		jdbc.update("update matching_participant set blocked_at=? where matching_id=? and user_id=?",
			Timestamp.from(testClock.instant()), room.getPairId(), firstId);
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
		assertThat(chats.room(next.getPublicId(), firstId).canSend()).isTrue();
		assertThat(chats.messages(next.getPublicId(), firstId, null, 20).items()).isEmpty();
		assertThat(chats.send(next.getPublicId(), firstId, UUID.randomUUID(), UUID.randomUUID(), "after reconsent")
			.text()).isEqualTo("after reconsent");
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

	@Test
	void roomPaginationUsesCreatedAtThenIdAndKeepsOpaqueCursor() throws Exception {
		ChatRoom restored = pageRoom(room.getCreatedAt().minusSeconds(120));
		ChatRoom tied = pageRoom(room.getCreatedAt());
		var page = chats.list(firstId, null, 1);
		assertThat(page.items()).extracting(v -> v.chatRoomId()).containsExactly(tied.getId());
		String cursor = page.nextCursor();
		assertThat(new String(java.util.Base64.getUrlDecoder().decode(cursor),
			java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("r|" + firstId + "|" + tied.getId());
		page = chats.list(firstId, cursor, 1);
		assertThat(page.items()).extracting(v -> v.chatRoomId()).containsExactly(room.getId());
		page = chats.list(firstId, page.nextCursor(), 1);
		assertThat(page.items()).extracting(v -> v.chatRoomId()).containsExactly(restored.getId());
		assertThat(page.nextCursor()).isNull();
		assertCode(() -> chats.list(secondId, cursor, 1), ErrorCode.INVALID_REQUEST);
		assertCode(() -> chats.list(firstId, "invalid!", 1), ErrorCode.INVALID_REQUEST);
	}

	@Test
	void roomPaginationAdvancesBothKeysAcrossMoreThanOneFilteredBatch() throws Exception {
		for (int i = 1; i <= 101; i++) {
			pageRoom(room.getCreatedAt().plusSeconds(i));
		}
		// Isolate the scan boundary while the other tests exercise real eligibility checks.
		doAnswer(call -> ((ChatRoom)call.getArgument(0)).getId().equals(room.getId()))
			.when(chats).available(any(ChatRoom.class), eq(firstId));
		var page = chats.list(firstId, null, 1);
		assertThat(page.items()).extracting(v -> v.chatRoomId()).containsExactly(room.getId());
		assertThat(page.nextCursor()).isNull();
	}

	private ChatRoom pageRoom(Instant createdAt) throws Exception {
		var partner = login("google", newSocialId());
		completeOnboarding(partner);
		long partnerId = userIdOf(partner);
		MatchProposal proposal = proposalRows.saveAndFlush(MatchProposal.proposed(firstId, partnerId, createdAt));
		ChatRoom saved = rooms.saveAndFlush(new ChatRoom(proposal.getId(), proposal.getId(), createdAt));
		chatMembers.saveAllAndFlush(List.of(new ChatMember(saved.getId(), firstId),
			new ChatMember(saved.getId(), partnerId)));
		return saved;
	}

	private List<String> noticeStates() {
		return jdbc.queryForList("select notice_state from chat_participant where chat_id=? order by user_id",
			String.class, room.getId());
	}

	private MessageView send(String text) {
		return chats.send(room.getPublicId(), firstId, UUID.randomUUID(), UUID.randomUUID(), text);
	}

	private String path() {
		return "/api/chat-rooms/" + room.getPublicId();
	}

	private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
	}
}
