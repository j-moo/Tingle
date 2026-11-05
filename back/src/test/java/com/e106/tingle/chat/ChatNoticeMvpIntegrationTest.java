package com.e106.tingle.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

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
import com.e106.tingle.chat.notice.ChatNoticeJobs;
import com.e106.tingle.chat.notice.ChatNoticeService;
import com.e106.tingle.chat.notice.NoticeDeliveryStore;
import com.e106.tingle.chat.notice.NoticeDispatcher;
import com.e106.tingle.chat.notice.NoticeScheduler;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.ProposalService;
import com.e106.tingle.push.PushGateway;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class ChatNoticeMvpIntegrationTest extends IntegrationTestSupport {

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
	@Autowired
	private ChatNoticeService notices;
	@Autowired
	private ChatNoticeJobs jobs;
	@Autowired
	private NoticeDispatcher dispatcher;
	@Autowired
	private NoticeDeliveryStore delivery;
	@Autowired
	private PlatformTransactionManager transactions;
	@Autowired
	private org.springframework.context.ApplicationContext context;
	@Autowired
	private Clock testClock;
	@MockitoBean
	private PushGateway gateway;
	private long firstId;
	private long secondId;
	private ChatRoom room;

	@BeforeEach
	void prepareChat() throws Exception {
		var first = login("google", newSocialId());
		var second = login("google", newSocialId());
		completeOnboarding(first);
		completeOnboarding(second);
		firstId = userIdOf(first);
		secondId = userIdOf(second);
		var proposal = proposalRows.saveAndFlush(MatchProposal.proposed(firstId, secondId, testClock.instant()));
		participantRows.saveAllAndFlush(List.of(new MatchingParticipant(proposal.getId(), firstId),
			new MatchingParticipant(proposal.getId(), secondId)));
		proposals.decide(proposal.getId(), firstId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposal.getId(), firstId).profileVersion());
		proposals.decide(proposal.getId(), secondId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposal.getId(), secondId).profileVersion());
		room = rooms.findBySourceProposalId(proposal.getId()).orElseThrow();
	}

	@Test
	void defaultMvpCreatesNoDedicatedNoticesAndKeepsChatAccess() {
		String before = state();
		assertThat(before).doesNotContain("FIRST_ENTRY", "END_NOTICE");
		assertThat(context.getBeansOfType(NoticeScheduler.class)).isEmpty();
		for (long seconds : List.of(3601L, 3600L, 1L)) {
			given(testClock.instant()).willReturn(room.getExpiresAt().minusSeconds(seconds));
			assertThat(notices.enter(room.getId(), firstId, UUID.randomUUID()).notices()).isEmpty();
			assertThat(notices.list(room.getId(), firstId).items()).isEmpty();
			assertThat(chats.room(room.getId(), firstId).canSend()).isTrue();
		}
		var sent = chats.send(room.getId(), firstId, UUID.randomUUID(), UUID.randomUUID(), "hello");
		assertThat(chats.messages(room.getId(), secondId, null, 20).items())
			.extracting(v -> v.messageId()).containsExactly(sent.messageId());
		assertThat(state()).isEqualTo(before);
	}

	@Test
	void disabledDispatchPreservesLegacyJobsAndStillAllowsOwnedAcknowledgement() {
		var tx = new TransactionTemplate(transactions);
		var firstJob = tx.execute(status -> jobs.ensure(room, firstId, "END_NOTICE", testClock.instant()));
		tx.execute(status -> jobs.ensure(room, secondId, "END_NOTICE", testClock.instant()));
		given(testClock.instant()).willReturn(room.getExpiresAt().minusSeconds(3600));
		assertThat(delivery.claim()).isNotNull();
		given(testClock.instant()).willReturn(room.getExpiresAt().minusSeconds(1));
		given(gateway.ready()).willReturn(true);
		String before = state();
		assertThat(before).contains("RUNNING", "PENDING");
		assertThat(dispatcher.runDue()).isZero();
		assertThat(state()).isEqualTo(before);
		then(gateway).shouldHaveNoInteractions();
		assertThat(notices.list(room.getId(), firstId).items()).isEmpty();
		UUID key = UUID.randomUUID();
		notices.ack(room.getId(), firstJob.getPublicId(), firstId, key);
		notices.ack(room.getId(), firstJob.getPublicId(), firstId, key);
		assertThat(state()).contains("ackedAt");
		assertThat(chats.room(room.getId(), firstId).canRead()).isTrue();
	}

	private String state() {
		return jdbc.queryForList("select notice_state::text from chat_participant where chat_id=? order by user_id",
			String.class, room.getId()).toString();
	}
}
