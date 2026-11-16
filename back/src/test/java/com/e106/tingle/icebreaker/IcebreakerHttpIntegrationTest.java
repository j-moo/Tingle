package com.e106.tingle.icebreaker;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.chat.ChatRoom;
import com.e106.tingle.chat.ChatRoomRepository;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.ProposalService;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실제 운영 bean 선택을 검증하며 외부 AI 대신 테스트 소유의 loopback 주소만 설정한다. */
@SpringBootTest(properties = "app.icebreaker.scheduling-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IcebreakerHttpIntegrationTest extends IntegrationTestSupport {
	private static final AtomicInteger REQUESTS = new AtomicInteger();
	private static final AtomicReference<Throwable> SERVER_FAILURE = new AtomicReference<>();
	private static Path directory;
	private static Path artifact;
	private static Path descriptor;
	private static HttpServer server;
	private static Path signingKey;
	private static KeyPair keys;
	private static int responseStatus;
	private static String responseBody;
	private static boolean closeDuringResponse;
	private static volatile boolean dropResponse;
	private static volatile boolean stallResponse;
	private static CountDownLatch responseRelease = new CountDownLatch(0);
	private static java.util.function.LongConsumer closeRoom;
	private static String expectedCommonTopic;
	private static Runnable withdrawDuringResponse;

	@Autowired
	private IcebreakerGateway gateway;
	@Autowired
	private IcebreakerProcessor processor;
	@Autowired
	private ProposalService proposals;
	@Autowired
	private ProposalRepository proposalRows;
	@Autowired
	private MatchingParticipantRepository participants;
	@Autowired
	private ChatRoomRepository rooms;
	@Autowired
	private IcebreakerTopicConsentService topicConsents;

	@DynamicPropertySource
	static void configureSyntheticServiceAuthentication(DynamicPropertyRegistry registry) throws Exception {
		directory = Files.createTempDirectory("tingle-icebreaker-boundary-");
		artifact = directory.resolve("artifact.json");
		descriptor = directory.resolve("manifest.json");
		byte[] content = "{}".getBytes(StandardCharsets.UTF_8);
		Files.write(artifact, content);
		Map<String, Object> manifest = new HashMap<>();
		manifest.put("releaseId", UUID.randomUUID().toString());
		for (String name : List.of("surveyVersion", "wordingVersion", "scoringVersion", "typeModelVersion",
			"analysisVersion", "typeReferenceVersion", "matchingVersion", "productPolicyVersion", "engineVersion")) {
			manifest.put(name, "synthetic-v1");
		}
		manifest.put("contentHash", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
		Files.writeString(descriptor, new ObjectMapper().writeValueAsString(
			Map.of("manifest", manifest, "artifact", artifact.toUri().toString())));
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/internal/ai/icebreakers", exchange -> {
			REQUESTS.incrementAndGet();
			boolean stalled = stallResponse;
			CountDownLatch release = responseRelease;
			try (exchange) {
				String bearer = exchange.getRequestHeaders().getFirst("Authorization");
				String[] jwt = bearer.substring(7).split("\\.");
				Signature verifier = Signature.getInstance("SHA256withRSA");
				verifier.initVerify(keys.getPublic());
				verifier.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
				assertThat(verifier.verify(Base64.getUrlDecoder().decode(jwt[2]))).isTrue();
				ObjectMapper mapper = new ObjectMapper();
				JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(jwt[1]));
				assertThat(claims.path("scope").asString()).isEqualTo("ai:execute");
				assertThat(claims.path("aud").asString()).isEqualTo("urn:tingle:ai:test");
				JsonNode body = mapper.readTree(exchange.getRequestBody());
				assertThat(body.size()).isEqualTo(expectedCommonTopic == null ? 3 : 4);
				if (expectedCommonTopic == null) {
					assertThat(body.has("commonTopicKey")).isFalse();
				} else {
					assertThat(body.path("commonTopicKey").asString()).isEqualTo(expectedCommonTopic);
				}
				assertThat(body.has("messages")).isFalse();
				assertThat(body.path("attemptId").asString())
					.isEqualTo(exchange.getRequestHeaders().getFirst("X-Request-Id"));
				if (closeDuringResponse) {
					closeRoom.accept(body.path("contextId").asLong());
				}
				if (withdrawDuringResponse != null) {
					withdrawDuringResponse.run();
				}
				if (dropResponse) {
					return;
				}
				if (stalled) {
					exchange.sendResponseHeaders(200, 0);
					exchange.getResponseBody().write('{');
					exchange.getResponseBody().flush();
					release.await(5, TimeUnit.SECONDS);
					return;
				}
				byte[] result = responseBody.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(responseStatus, result.length);
				exchange.getResponseBody().write(result);
			} catch (Throwable exception) {
				if (stalled && exception instanceof IOException) {
					return;
				}
				SERVER_FAILURE.set(exception);
				throw new IOException(exception);
			}
		});
		server.start();
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		keys = generator.generateKeyPair();
		signingKey = directory.resolve("synthetic-private.pem");
		Files.writeString(signingKey, "-----BEGIN PRIVATE KEY-----\n"
			+ Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())
			+ "\n-----END PRIVATE KEY-----\n");
		registry.add("app.ai.service.enabled", () -> true);
		registry.add("app.ai.service.environment", () -> "test");
		registry.add("app.ai.service.key-id", () -> "synthetic-icebreaker");
		registry.add("app.ai.service.private-key-file", () -> signingKey.toString());
		registry.add("app.ai.service.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
		registry.add("app.icebreaker.service-token", () -> "synthetic-static-token");
		registry.add("app.icebreaker.endpoint", () -> "http://127.0.0.1:" + server.getAddress().getPort()
			+ "/internal/ai/icebreakers");
		registry.add("app.icebreaker.manifest-location", () -> descriptor.toUri().toString());
	}

	@AfterAll
	static void releaseOnlyTestOwnedResources() throws IOException {
		if (server != null) {
			server.stop(0);
		}
		if (descriptor != null) {
			Files.deleteIfExists(descriptor);
		}
		if (artifact != null) {
			Files.deleteIfExists(artifact);
		}
		if (signingKey != null) {
			Files.deleteIfExists(signingKey);
		}
		if (directory != null) {
			Files.deleteIfExists(directory);
		}
	}

	@BeforeEach
	void resetSyntheticResponse() {
		REQUESTS.set(0);
		SERVER_FAILURE.set(null);
		responseStatus = 200;
		responseBody = "{\"questions\":[\"첫 질문\",\"두 번째 질문\",\"세 번째 질문\"],"
			+ "\"source\":\"GENERATED\",\"promptVersion\":\"synthetic-v1\"}";
		closeDuringResponse = false;
		dropResponse = false;
		stallResponse = false;
		expectedCommonTopic = null;
		withdrawDuringResponse = null;
		responseRelease = new CountDownLatch(0);
		closeRoom = roomId -> jdbc.update("update chat set closed_at=now(),close_reason='LEFT' where id=?", roomId);
	}

	@Test
	void authenticatedHttpResultIsValidatedAndStoredWithoutSendingChatContent() throws Exception {
		assertThat(gateway).isExactlyInstanceOf(HttpIcebreakerGateway.class);
		assertThat(gateway.enabled()).isTrue();
		executeAndAssert("GENERATED", 1);
	}

	@Test
	void invalidSuccessfulOutputFallsBackImmediatelyWithoutRetry() throws Exception {
		responseBody = "{\"questions\":[\"중복\",\"중복\",\"중복\"],"
			+ "\"source\":\"GENERATED\",\"promptVersion\":\"synthetic-v1\"}";
		executeAndAssert("GENERAL_FALLBACK", 1);
	}

	@Test
	void sharedTopicTraversesAuthenticatedHttpAndStoredQuestionsAreErasedOnWithdrawal() throws Exception {
		expectedCommonTopic = "rest";
		responseBody = commonResponse();
		JsonNode first = newReadyUser();
		JsonNode second = newReadyUser();
		prepareSharedTopic(userIdOf(first));
		prepareSharedTopic(userIdOf(second));
		ChatRoom room = createRoom(userIdOf(first), userIdOf(second));
		String path = "/api/chat-rooms/" + room.getId() + "/icebreakers";
		perform(post(path).header("Idempotency-Key", UUID.randomUUID()), first, null)
			.andExpect(status().isAccepted());
		assertThat(processor.process(room.getId())).isTrue();
		perform(get(path), second, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.source").value("COMMON_TOPIC_TEMPLATE"))
			.andExpect(jsonPath("$.promptVersion").value("q040-template-v1"))
			.andExpect(jsonPath("$.questions[0]").value("공통 휴식 질문"))
			.andExpect(jsonPath("$.questions.length()").value(3));
		topicConsents.update(userIdOf(second), false, IcebreakerTopics.CONSENT_VERSION);
		perform(get(path), first, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.source").value("GENERAL_FALLBACK"))
			.andExpect(jsonPath("$.questions.length()").value(3));
		assertThat(jdbc.queryForObject("select icebreakers::text from chat where id=?", String.class, room.getId()))
			.doesNotContain("공통 휴식 질문", "commonTopicKey", "topicSelection");
		assertThat(SERVER_FAILURE.get()).isNull();
		assertThat(REQUESTS.get()).isEqualTo(1);
	}

	@Test
	void withdrawalWhileAuthenticatedHttpRunsPreventsLateCommonQuestionsFromBeingStored() throws Exception {
		expectedCommonTopic = "rest";
		responseBody = commonResponse();
		JsonNode first = newReadyUser();
		JsonNode second = newReadyUser();
		prepareSharedTopic(userIdOf(first));
		prepareSharedTopic(userIdOf(second));
		ChatRoom room = createRoom(userIdOf(first), userIdOf(second));
		long withdrawingUserId = userIdOf(second);
		withdrawDuringResponse = () -> topicConsents.update(withdrawingUserId, false, IcebreakerTopics.CONSENT_VERSION);
		String path = "/api/chat-rooms/" + room.getId() + "/icebreakers";
		perform(post(path).header("Idempotency-Key", UUID.randomUUID()), first, null)
			.andExpect(status().isAccepted());
		assertThat(processor.process(room.getId())).isFalse();
		perform(get(path), first, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.source").value("GENERAL_FALLBACK"))
			.andExpect(jsonPath("$.questions.length()").value(3));
		assertThat(jdbc.queryForObject("select icebreakers::text from chat where id=?", String.class, room.getId()))
			.doesNotContain("공통 휴식 질문", "commonTopicKey", "topicSelection");
		assertThat(SERVER_FAILURE.get()).isNull();
		assertThat(REQUESTS.get()).isEqualTo(1);
	}

	@Test
	void commonHttpResponseWithoutARequestedTopicFallsBackImmediately() throws Exception {
		responseBody = commonResponse();
		executeAndAssert("GENERAL_FALLBACK", 1);
	}

	@Test
	void actual503FallsBackImmediatelyEvenWhenTheProviderFailureIsTransient() throws Exception {
		responseStatus = 503;
		responseBody = "{\"code\":\"SERVICE_UNAVAILABLE\"}";
		executeAndAssert("GENERAL_FALLBACK", 1);
	}

	@Test
	void otherServerFailuresRetryWithinTheOriginalBudgetThenFallBack() throws Exception {
		responseStatus = 502;
		responseBody = "{\"code\":\"SERVICE_UNAVAILABLE\"}";
		executeAndAssert("GENERAL_FALLBACK", 3);
	}

	@Test
	void interruptedHttpConnectionsConsumeOnlyThreeAttemptsBeforePersistingFallback() throws Exception {
		dropResponse = true;
		executeAndAssert("GENERAL_FALLBACK", 3);
	}

	@Test
	void stalledBodyCannotExtendTheOriginalFiveMinuteBudgetOrStoreALateSuccess() throws Exception {
		stallResponse = true;
		responseRelease = new CountDownLatch(1);
		try {
			JsonNode first = newReadyUser();
			JsonNode second = newReadyUser();
			ChatRoom room = createRoom(userIdOf(first), userIdOf(second));
			String path = "/api/chat-rooms/" + room.getId() + "/icebreakers";
			perform(post(path).header("Idempotency-Key", UUID.randomUUID()), first, null)
				.andExpect(status().isAccepted());
			String deadline = jdbc.queryForObject("select icebreakers->>'deadlineAt' from chat where id=?",
				String.class, room.getId());
			timeShift.set(Duration.ofMillis(299000));
			assertThat(processor.process(room.getId())).isTrue();
			timeShift.set(Duration.ofSeconds(301));
			assertThat(processor.process(room.getId())).isFalse();
			perform(get(path), second, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.source").value("GENERAL_FALLBACK"))
				.andExpect(jsonPath("$.questions.length()").value(3));
			assertThat(REQUESTS.get()).isEqualTo(1);
			assertThat(jdbc.queryForObject("select icebreakers->>'deadlineAt' from chat where id=?",
				String.class, room.getId())).isEqualTo(deadline);
		} finally {
			responseRelease.countDown();
		}
	}

	@Test
	void roomClosedWhileHttpRunsRejectsTheLateResult() throws Exception {
		closeDuringResponse = true;
		JsonNode first = newReadyUser();
		JsonNode second = newReadyUser();
		ChatRoom room = createRoom(userIdOf(first), userIdOf(second));
		String path = "/api/chat-rooms/" + room.getId() + "/icebreakers";
		perform(post(path).header("Idempotency-Key", UUID.randomUUID()), first, null)
			.andExpect(status().isAccepted());
		processor.process(room.getId());
		perform(get(path), second, null).andExpect(status().isGone());
		assertThat(SERVER_FAILURE.get()).isNull();
		assertThat(REQUESTS.get()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select icebreakers->>'status' from chat where id=?", String.class,
			room.getId())).isEqualTo("FAILED");
	}

	private void executeAndAssert(String source, int attempts) throws Exception {
		JsonNode first = newReadyUser();
		JsonNode second = newReadyUser();
		ChatRoom room = createRoom(userIdOf(first), userIdOf(second));
		String path = "/api/chat-rooms/" + room.getId() + "/icebreakers";
		perform(post(path).header("Idempotency-Key", UUID.randomUUID()), first, null)
			.andExpect(status().isAccepted());
		String deadline = jdbc.queryForObject("select icebreakers->>'deadlineAt' from chat where id=?",
			String.class, room.getId());
		for (int attempt = 0; attempt < attempts; attempt++) {
			assertThat(processor.process(room.getId())).isTrue();
			timeShift.set(Duration.ofSeconds(10L * (attempt + 1)));
		}
		perform(get(path), second, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.source").value(source))
			.andExpect(jsonPath("$.questions.length()").value(3));
		if ("GENERAL_FALLBACK".equals(source)) {
			IcebreakerGateway.Generated fallback = jsonMapper.readValue(
				getClass().getResourceAsStream("/icebreakers/general-ko-v1.json"), IcebreakerGateway.Generated.class);
			assertThat(fallback).isNotNull();
			perform(get(path), second, null)
				.andExpect(jsonPath("$.questions").value(fallback.questions()))
				.andExpect(jsonPath("$.promptVersion").value(fallback.promptVersion()));
		}
		assertThat(processor.process(room.getId())).isFalse();
		assertThat(SERVER_FAILURE.get()).isNull();
		assertThat(REQUESTS.get()).isEqualTo(attempts);
		assertThat(jdbc.queryForObject("select icebreakers->>'deadlineAt' from chat where id=?",
			String.class, room.getId())).isEqualTo(deadline);
		assertThat(jdbc.queryForObject("select count(*) from chat_message where chat_id=?", Long.class,
			room.getId())).isZero();
	}

	private JsonNode newReadyUser() throws Exception {
		JsonNode tokens = login("google", newSocialId());
		completeOnboarding(tokens);
		return tokens;
	}

	private void prepareSharedTopic(long userId) {
		topicConsents.update(userId, true, IcebreakerTopics.CONSENT_VERSION);
		Long surveyId = jdbc.queryForObject("insert into survey(user_id,survey_version,completed_at,revision) "
			+ "values (?,'SIS-Q-1.1-100',?,0) returning id", Long.class, userId, Timestamp.from(clock.instant()));
		jdbc.update("insert into survey_answer(survey_id,question_id,answer_type,answer_value,answered_at) "
			+ "values (?,'Q040','ARRAY',?,?)", surveyId, "[\"talk\",\"rest\"]", Timestamp.from(clock.instant()));
	}

	private String commonResponse() {
		return "{\"questions\":[\"공통 휴식 질문\",\"두 번째 질문\",\"세 번째 질문\"],"
			+ "\"source\":\"COMMON_TOPIC_TEMPLATE\",\"promptVersion\":\"q040-template-v1\"}";
	}

	private ChatRoom createRoom(long firstUser, long secondUser) {
		MatchProposal proposal = proposalRows.saveAndFlush(
			MatchProposal.proposed(firstUser, secondUser, clock.instant()));
		participants.saveAllAndFlush(List.of(new MatchingParticipant(proposal.getId(), firstUser),
			new MatchingParticipant(proposal.getId(), secondUser)));
		proposals.decide(proposal.getId(), firstUser, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposal.getId(), firstUser).profileVersion());
		proposals.decide(proposal.getId(), secondUser, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
			proposals.profile(proposal.getId(), secondUser).profileVersion());
		return rooms.findBySourceProposalId(proposal.getId()).orElseThrow();
	}
}
