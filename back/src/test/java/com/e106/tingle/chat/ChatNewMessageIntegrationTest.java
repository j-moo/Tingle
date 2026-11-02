package com.e106.tingle.chat;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.util.List;
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
class ChatNewMessageIntegrationTest extends IntegrationTestSupport {
	@Autowired
	private ChatService chats;
	@Autowired
	private ChatRoomRepository rooms;
	@Autowired
	private ProposalService proposals;
	@Autowired
	private ProposalRepository proposalRows;
	@Autowired
	private MatchingParticipantRepository participantRows;
	private JsonNode first;
	private JsonNode second;
	private String secondSocialId;
	private long firstId;
	private long secondId;
	private ChatRoom room;

	@BeforeEach
	void prepareChat() throws Exception {
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
	}

	@Test
	void returnsRequiredFalseInitiallyAndExcludesOwnMessages() throws Exception {
		assertState(first, false);
		assertState(second, false);
		send(firstId, "first message");
		assertState(first, false);
		assertState(second, true);
	}

	@Test
	void explicitNoncontiguousAcknowledgementsKeepTheUnacknowledgedGap() throws Exception {
		MessageView older = send(firstId, "older");
		MessageView newer = send(firstId, "newer");
		chats.read(room.getPublicId(), secondId, List.of(newer.messageId()));
		assertState(second, true);
		chats.read(room.getPublicId(), secondId, List.of(newer.messageId(), newer.messageId()));
		assertState(second, true);
		chats.read(room.getPublicId(), secondId, List.of(older.messageId()));
		assertState(second, false);
		send(secondId, "my reply does not count as new to me");
		assertState(second, false);
		assertState(first, true);
	}

	@Test
	void listRoomHistoryAndReloginDoNotAcknowledgeMessages() throws Exception {
		send(firstId, "persisted while disconnected");
		assertState(second, true);
		perform(get(path() + "/messages"), second, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].readByPartner").doesNotExist());
		String previousState = readState();
		logout(second.path("refreshToken").asString()).andExpect(status().isNoContent());
		JsonNode reconnected = login("google", secondSocialId);
		assertState(reconnected, true);
		assertThat(readState()).isEqualTo(previousState).isEqualTo("{}");
		assertThat(chats.room(room.getPublicId(), secondId).hasNewMessage()).isTrue();
	}

	@Test
	void concurrentSendAndAcknowledgementCannotClearTheNewArrival() throws Exception {
		MessageView older = send(firstId, "displayed before arrival");
		runConcurrently(
			() -> chats.read(room.getPublicId(), secondId, List.of(older.messageId())),
			() -> send(firstId, "arriving concurrently"));
		assertState(second, true);
		List<Long> ids = chats.messages(room.getPublicId(), secondId, null, 100).items().stream()
			.map(MessageView::messageId).toList();
		chats.read(room.getPublicId(), secondId, ids);
		assertState(second, false);
	}

	@Test
	void closedRoomsReturnFalseAndAreExcludedFromTheList() throws Exception {
		send(firstId, "not acknowledged before expiry");
		timeShift.set(Duration.ofDays(8));
		assertThat(chats.room(room.getPublicId(), secondId).hasNewMessage()).isFalse();
		assertThat(chats.list(secondId, null, 100).items()).isEmpty();
		assertThat(readState()).isEqualTo("{}");
	}

	private MessageView send(long sender, String text) {
		return chats.send(room.getPublicId(), sender, UUID.randomUUID(), UUID.randomUUID(), text);
	}

	private void assertState(JsonNode tokens, boolean expected) throws Exception {
		perform(get(path()), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.hasNewMessage").value(expected))
			.andExpect(jsonPath("$.lastReadMessageSeq").doesNotExist())
			.andExpect(jsonPath("$.readByPartner").doesNotExist());
		perform(get("/api/chat-rooms"), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].chatRoomId").value(room.getPublicId()))
			.andExpect(jsonPath("$.items[0].hasNewMessage").value(expected));
	}

	private String readState() {
		return jdbc.queryForObject("select read_state from chat_participant where chat_id=? and user_id=?",
			String.class, room.getId(), secondId);
	}

	private String path() {
		return "/api/chat-rooms/" + room.getPublicId();
	}
}
