package com.e106.tingle.chat;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.ProposalService;

import jakarta.persistence.EntityManagerFactory;
import tools.jackson.databind.JsonNode;

/** 동일한 격리 PostgreSQL 입력에서 쿼리 수와 로컬 지연을 측정한다. 운영 SLO 증거가 아니다. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class ChatListLoadIntegrationTest extends IntegrationTestSupport {
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
	private EntityManagerFactory factory;

	@Test
	void twentyRoomsAndTwoThousandMessagesPreserveUnreadStateUnderConcurrentQueries() throws Exception {
		var actor = login("google", newSocialId());
		completeOnboarding(actor);
		long own = userIdOf(actor);
		for (int index = 0; index < 20; index++) {
			var partner = login("google", newSocialId());
			completeOnboarding(partner);
			long peer = userIdOf(partner);
			MatchProposal row = proposalRows.saveAndFlush(MatchProposal.proposed(own, peer, clock.instant()));
			participantRows.saveAllAndFlush(List.of(new MatchingParticipant(row.getId(), own),
				new MatchingParticipant(row.getId(), peer)));
			for (long id : List.of(own, peer)) {
				proposals.decide(row.getId(), id, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
					proposals.profile(row.getId(), id).profileVersion());
			}
			ChatRoom room = rooms.findBySourceProposalId(row.getId()).orElseThrow();
			long sender = jdbc.queryForObject("select id from chat_participant where chat_id=? and user_id=?",
				Long.class, room.getId(), peer);
			jdbc.update("insert into chat_message(chat_id,sender_participant_id,client_message_id,request_key,"
				+ "request_hash,seq,body,created_at,push_state) select ?,?,gen_random_uuid(),gen_random_uuid(),"
				+ "decode(repeat('a',64),'hex'),n,'synthetic load',?,'{}'::jsonb from generate_series(1,100) n",
				room.getId(), sender, Timestamp.from(clock.instant()));
			jdbc.update("update chat set last_message_seq=100 where id=?", room.getId());
		}
		chats.list(own, null, 20);
		var statistics = factory.unwrap(SessionFactory.class).getStatistics();
		List<Long> nanos = new ArrayList<>();
		long statements = 0;
		for (int count = 0; count < 8; count++) {
			statistics.clear();
			long started = System.nanoTime();
			var page = chats.list(own, null, 20);
			nanos.add(System.nanoTime() - started);
			statements = statistics.getPrepareStatementCount();
			assertThat(page.items()).hasSize(20).allMatch(item -> item.hasNewMessage());
			assertThat(page.nextCursor()).isNull();
		}
		assertThat(statements).as("bounded Hibernate statements for the fixed twenty-room page")
			.isLessThanOrEqualTo(43);
		nanos.sort(Long::compare);
		System.out.println("CHAT_LIST_LOAD rooms=20 messages=2000 concurrency=8 samples=8 hibernateStatements="
			+ statements + " p95Millis=" + nanos.getLast() / 1_000_000.0);
		var simultaneous = runConcurrently(() -> chats.list(own, null, 20), () -> chats.list(own, null, 20),
			() -> chats.list(own, null, 20), () -> chats.list(own, null, 20),
			() -> chats.list(own, null, 20), () -> chats.list(own, null, 20),
			() -> chats.list(own, null, 20), () -> chats.list(own, null, 20));
		assertThat(simultaneous).allSatisfy(page -> assertThat(page.items()).hasSize(20)
			.allMatch(item -> item.hasNewMessage()));
		assertThat(jdbc.queryForObject("select count(*) from chat_participant where user_id=? "
			+ "and read_state<>'{}'::jsonb", Long.class, own)).isZero();
		measureHttp("CHAT_LIST_HTTP rooms=20 messages=2000", () -> perform(get("/api/chat-rooms"), actor, null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20)));
		measureMessagesAndProposals(actor, own);

	}

	private void measureMessagesAndProposals(JsonNode actor, long own) throws Exception {
		long room = chats.list(own, null, 20).items().getFirst().chatRoomId();
		long sender = jdbc.queryForObject("select id from chat_participant where chat_id=? and user_id<>?",
			Long.class, room, own);
		jdbc.update("insert into chat_message(chat_id,sender_participant_id,client_message_id,request_key,"
			+ "request_hash,seq,body,created_at,push_state) select ?,?,gen_random_uuid(),gen_random_uuid(),"
			+ "decode(repeat('a',64),'hex'),n,'synthetic load',?,'{}'::jsonb from generate_series(101,10000) n",
			room, sender, Timestamp.from(clock.instant()));
		jdbc.update("update chat set last_message_seq=10000 where id=?", room);
		measureHttp("MESSAGE_PAGE_HTTP roomMessages=10000 limit=100", () -> perform(get("/api/chat-rooms/"
			+ room + "/messages").param("limit", "100"), actor, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(100)));
		for (int index = 0; index < 20; index++) {
			JsonNode peer = login("google", newSocialId());
			completeOnboarding(peer);
			long partner = userIdOf(peer);
			long proposal = proposalRows.saveAndFlush(MatchProposal.proposed(own, partner, clock.instant())).getId();
			participantRows.saveAllAndFlush(List.of(new MatchingParticipant(proposal, own),
				new MatchingParticipant(proposal, partner)));
		}
		measureHttp("PROPOSAL_LIST_HTTP pending=20 limit=20", () -> perform(get("/api/match-proposals")
			.param("limit", "20"), actor, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(20)));
		assertThat(jdbc.queryForObject("select count(*) from chat_participant where user_id=? "
			+ "and read_state<>'{}'::jsonb", Long.class, own)).isZero();
	}

	private void measureHttp(String scenario, Callable<ResultActions> query) throws Exception {
		query.call();
		var statistics = factory.unwrap(SessionFactory.class).getStatistics();
		List<Long> nanos = new ArrayList<>();
		long statements = 0;
		for (int sample = 0; sample < 8; sample++) {
			statistics.clear();
			long start = System.nanoTime();
			query.call();
			nanos.add(System.nanoTime() - start);
			statements = statistics.getPrepareStatementCount();
		}
		nanos.sort(Long::compare);
		System.out.println(scenario + " samples=8 concurrency=8 hibernateStatements=" + statements
			+ " maxMillis=" + nanos.getLast() / 1_000_000.0);
		assertThat(runConcurrently(query, query, query, query, query, query, query, query)).hasSize(8);
	}

}
