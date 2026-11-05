package com.e106.tingle.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.chat.deletion.restore.RestoreServingGateFilter;
import com.e106.tingle.chat.dto.MessagePage;
import com.e106.tingle.chat.dto.MessageView;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.ProposalService;

import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = "app.chat-realtime.poll-ms=100")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class ChatRealtimeIntegrationTest extends IntegrationTestSupport {
	@LocalServerPort
	private int port;
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
	private PlatformTransactionManager transactions;
	@Autowired
	private JwtDecoder decoder;
	@MockitoSpyBean
	private RestoreServingGateFilter restoreGate;
	private JsonNode first;
	private JsonNode second;
	private long firstId;
	private long secondId;
	private ChatRoom room;
	private final List<SocketEvents> sockets = new ArrayList<>();

	@BeforeEach
	void prepareChat() throws Exception {
		first = login("google", newSocialId());
		second = login("google", newSocialId());
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

	@AfterEach
	void closeSockets() {
		sockets.forEach(s -> s.socket.abort());
	}

	@Test
	void twoAccountsReceiveCommittedHttpMessageWithoutAcknowledgingOrDuplicatingIt() throws Exception {
		SocketEvents sender = connect(first, 0);
		SocketEvents receiver = connect(second, 0);
		UUID clientId = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		String body = toJson(Map.of("clientMessageId", clientId, "text", "hello https://example.com"));
		String response = perform(post(path() + "/messages").header("Idempotency-Key", key), first, body)
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long messageId = jsonMapper.readTree(response).path("messageId").asLong();
		assertThat(sender.next().path("message").path("sender").asString()).isEqualTo("ME");
		JsonNode event = receiver.next();
		assertThat(event.path("type").asString()).isEqualTo("CHAT_MESSAGE");
		assertThat(event.path("eventId").asString()).isEqualTo("chat-message:" + messageId);
		assertThat(event.path("message").path("sender").asString()).isEqualTo("PARTNER");
		assertThat(event.path("message").path("text").asString()).isEqualTo("hello [웹 주소 차단]");
		perform(post(path() + "/messages").header("Idempotency-Key", key), first, body)
			.andExpect(status().isCreated());
		assertThat(receiver.events.poll(400, TimeUnit.MILLISECONDS)).isNull();
		assertThat(readState()).isEqualTo("{}");
		assertThat(chats.room(room.getPublicId(), secondId).hasNewMessage()).isTrue();
	}

	@Test
	void reconnectAndHttpForwardPaginationRecoverInSequenceWithoutChangingHistoryContract() throws Exception {
		MessageView one = send("one");
		send("two");
		send("three");
		SocketEvents reconnected = connect(second, one.sequence());
		assertThat(reconnected.next().path("message").path("sequence").asLong()).isEqualTo(2);
		assertThat(reconnected.next().path("message").path("sequence").asLong()).isEqualTo(3);
		MessagePage firstPage = chats.messages(room.getPublicId(), secondId, null, 1, 0L);
		assertThat(firstPage.items().getFirst().sequence()).isEqualTo(1);
		MessagePage next = chats.messages(room.getPublicId(), secondId, firstPage.nextCursor(), 1, null);
		assertThat(next.items().getFirst().sequence()).isEqualTo(2);
		perform(get(path() + "/messages").param("afterSequence", "1"), second, null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].sequence").value(2));
		perform(get(path() + "/messages").param("afterSequence", "1").param("cursor", firstPage.nextCursor()),
			second, null).andExpect(status().isBadRequest());
		assertThat(chats.messages(room.getPublicId(), secondId, null, 1).items().getFirst().sequence()).isEqualTo(3);
		assertThat(readState()).isEqualTo("{}");
	}

	@Test
	void futurePositionsAreRejectedAndCurrentEndStillReceivesNewMessages() throws Exception {
		SocketEvents empty = connect(second, 0);
		assertHandshakeStatus(second, "afterSequence=1", 400);
		perform(get(path() + "/messages").param("afterSequence", "1"), second, null)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		MessageView committed = send("first committed");
		assertThat(empty.next().path("message").path("messageId").asLong()).isEqualTo(committed.messageId());
		assertHandshakeStatus(second, "afterSequence=" + Long.MAX_VALUE, 400);
		String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
			("ma|" + room.getPublicId() + "|" + Long.MAX_VALUE).getBytes(StandardCharsets.UTF_8));
		perform(get(path() + "/messages").param("cursor", forged), second, null)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		SocketEvents current = connect(second, committed.sequence());
		MessageView arriving = send("arrives after current-end subscription");
		assertThat(current.next().path("message").path("messageId").asLong()).isEqualTo(arriving.messageId());
		assertThat(chats.recover(room.getPublicId(), secondId, committed.sequence(), 100).items())
			.extracting(MessageView::messageId).containsExactly(arriving.messageId());
		assertThat(readState()).isEqualTo("{}");
	}

	@Test
	void rollbackNeverPublishesAndNextCommittedMessageKeepsTheSequence() throws Exception {
		SocketEvents receiver = connect(second, 0);
		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			send("rolled back");
			status.setRollbackOnly();
		});
		assertThat(receiver.events.poll(300, TimeUnit.MILLISECONDS)).isNull();
		send("committed");
		JsonNode event = receiver.next().path("message");
		assertThat(event.path("sequence").asLong()).isEqualTo(1);
		assertThat(event.path("text").asString()).isEqualTo("committed");
	}

	@Test
	void concurrentSendsAreDeliveredOnceInServerSequenceOrder() throws Exception {
		SocketEvents receiver = connect(second, 0);
		runConcurrently(() -> chats.send(room.getPublicId(), firstId, UUID.randomUUID(), UUID.randomUUID(), "one"),
			() -> chats.send(room.getPublicId(), secondId, UUID.randomUUID(), UUID.randomUUID(), "two"));
		JsonNode firstEvent = receiver.next().path("message");
		JsonNode secondEvent = receiver.next().path("message");
		assertThat(firstEvent.path("sequence").asLong()).isEqualTo(1);
		assertThat(secondEvent.path("sequence").asLong()).isEqualTo(2);
		assertThat(firstEvent.path("messageId").asLong()).isNotEqualTo(secondEvent.path("messageId").asLong());
		assertThat(receiver.events.poll(250, TimeUnit.MILLISECONDS)).isNull();
	}

	@Test
	void restoreGateFailureClosesAnExistingConnectionWithoutContent() throws Exception {
		SocketEvents receiver = connect(second, 0);
		doReturn(false).when(restoreGate).servingAllowed();
		JsonNode event = receiver.next();
		assertThat(event.path("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
		assertThat(event.has("message")).isFalse();
		assertThat(receiver.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
	}

	@Test
	void accessTokenExpiryClosesAnExistingConnection() throws Exception {
		SocketEvents receiver = connect(second, 0);
		var expiration = decoder.decode(second.path("accessToken").asString()).getExpiresAt();
		timeShift.set(Duration.between(clock.instant(), expiration.plusSeconds(1)));
		assertThat(receiver.next().path("code").asString()).isEqualTo("ACCESS_TOKEN_EXPIRED");
		assertThat(receiver.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
	}

	@Test
	void revokedLoginClosesExistingSubscriptionWithoutSendingFurtherContent() throws Exception {
		SocketEvents receiver = connect(second, 0);
		logout(second.path("refreshToken").asString()).andExpect(status().isNoContent());
		send("must not reach old login");
		assertThat(receiver.next().path("code").asString()).isEqualTo("SESSION_REVOKED");
		assertThat(receiver.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
		assertThat(receiver.events).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"blocked", "consent", "left", "withdrawn", "expiry"})
	void latestAccessRestrictionClosesAlreadySubscribedSocket(String restriction) throws Exception {
		SocketEvents receiver = connect(second, 0);
		switch (restriction) {
			case "blocked" -> jdbc.update("update matching_participant set blocked_at=? "
				+ "where matching_id=? and user_id=?", Timestamp.from(clock.instant()), room.getPairId(), firstId);
			case "consent" -> jdbc.update("insert into user_consents "
				+ "(user_id,consent_type,document_version,agreed,recorded_at) "
				+ "values (?, 'AI_RISK_DETECTION',?,false,?)",
				firstId, AI_RISK_VERSION, Timestamp.from(room.getCreatedAt().plusSeconds(1)));
			case "left" -> jdbc.update("update chat_participant set left_at=? where chat_id=? and user_id=?",
				Timestamp.from(clock.instant()), room.getId(), secondId);
			case "withdrawn" -> jdbc.update("update users set status='WITHDRAWN' where id=?", firstId);
			case "expiry" -> {
				Instant deadline = clock.instant().truncatedTo(ChronoUnit.MICROS);
				jdbc.update("update chat set created_at=?, expires_at=? where id=?",
					Timestamp.from(deadline.minus(Duration.ofDays(7))), Timestamp.from(deadline), room.getId());
				given(clock.instant()).willReturn(deadline);
				assertThat(rooms.findById(room.getId()).orElseThrow().active(deadline.minusNanos(1))).isTrue();
			}
			default -> throw new IllegalArgumentException("Unknown test case");
		}
		assertThat(receiver.next().path("code").asString()).isEqualTo("CHAT_CLOSED");
		assertThat(receiver.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
		assertThat(readState()).isEqualTo("{}");
	}

	@Test
	void handshakeRejectsOutsiderAndInvalidRecoveryPositionAndSocketDoesNotAcceptSends() throws Exception {
		JsonNode outsider = login("google", newSocialId());
		completeOnboarding(outsider);
		assertHandshakeStatus(outsider, "afterSequence=0", 404);
		assertHandshakeStatus(second, "afterSequence=-1", 400);
		assertHandshakeStatus(second, "token=not-accepted", 400);
		SocketEvents receiver = connect(second, 0);
		receiver.socket.sendText("{\"text\":\"not a send endpoint\"}", true).join();
		assertThat(receiver.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1003);
	}

	private MessageView send(String text) {
		return chats.send(room.getPublicId(), firstId, UUID.randomUUID(), UUID.randomUUID(), text);
	}

	private SocketEvents connect(JsonNode tokens, long after) throws Exception {
		SocketEvents events = new SocketEvents();
		events.socket = HttpClient.newHttpClient().newWebSocketBuilder()
			.header("Authorization", "Bearer " + tokens.path("accessToken").asString())
			.buildAsync(uri("afterSequence=" + after), events).get(10, TimeUnit.SECONDS);
		sockets.add(events);
		assertThat(events.next().path("type").asString()).isEqualTo("CHAT_SUBSCRIBED");
		return events;
	}

	private void assertHandshakeStatus(JsonNode tokens, String query, int expected) {
		assertThatThrownBy(() -> HttpClient.newHttpClient().newWebSocketBuilder()
			.header("Authorization", "Bearer " + tokens.path("accessToken").asString())
			.buildAsync(uri(query), new SocketEvents()).join())
			.hasCauseInstanceOf(WebSocketHandshakeException.class)
			.satisfies(exception -> assertThat(((WebSocketHandshakeException)exception.getCause())
				.getResponse().statusCode()).isEqualTo(expected));
	}

	private URI uri(String query) {
		return URI.create("ws://localhost:" + port + path() + "/events?" + query);
	}

	private String path() {
		return "/api/chat-rooms/" + room.getPublicId();
	}

	private String readState() {
		return jdbc.queryForObject("select read_state from chat_participant where chat_id=? and user_id=?",
			String.class, room.getId(), secondId);
	}

	private class SocketEvents implements WebSocket.Listener {
		private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
		private final CompletableFuture<Integer> closed = new CompletableFuture<>();
		private final StringBuilder fragment = new StringBuilder();
		private WebSocket socket;

		@Override
		public void onOpen(WebSocket webSocket) {
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			fragment.append(data);
			if (last) {
				events.add(fragment.toString());
				fragment.setLength(0);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			closed.complete(statusCode);
			return null;
		}

		JsonNode next() throws Exception {
			String value = events.poll(5, TimeUnit.SECONDS);
			assertThat(value).as("WebSocket event").isNotNull();
			return jsonMapper.readTree(value);
		}
	}
}
