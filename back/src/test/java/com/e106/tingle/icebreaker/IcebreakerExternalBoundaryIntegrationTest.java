package com.e106.tingle.icebreaker;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.DefaultResourceLoader;
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
class IcebreakerExternalBoundaryIntegrationTest extends IntegrationTestSupport {
	private static final AtomicInteger REQUESTS = new AtomicInteger();
	private static Path directory;
	private static Path artifact;
	private static Path descriptor;
	private static HttpServer server;

	@Autowired
	private IcebreakerGateway gateway;
	@Autowired
	private IcebreakerProperties properties;
	@Autowired
	private IcebreakerProcessor processor;
	@Autowired
	private ApplicationContext context;
	@Autowired
	private ProposalService proposals;
	@Autowired
	private ProposalRepository proposalRows;
	@Autowired
	private MatchingParticipantRepository participants;
	@Autowired
	private ChatRoomRepository rooms;

	@DynamicPropertySource
	static void configureOtherwiseValidExternalTransport(DynamicPropertyRegistry registry) throws Exception {
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
			try (exchange) {
				exchange.getRequestBody().readAllBytes();
				exchange.sendResponseHeaders(503, -1);
			}
		});
		server.start();
		registry.add("app.icebreaker.external-enabled", () -> true);
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
		if (directory != null) {
			Files.deleteIfExists(directory);
		}
	}

	@Test
	void configuredLegacyTransportCannotBypassPendingCommonAuthentication() throws Exception {
		assertThat(properties.externalEnabled()).isTrue();
		assertThat(properties.endpoint()).isNotBlank();
		assertThat(properties.serviceToken()).isNotBlank();
		HttpIcebreakerGateway boundedTransport = new HttpIcebreakerGateway(properties,
			new DefaultResourceLoader(), jsonMapper, clock);
		assertThat(boundedTransport.enabled()).isTrue();
		assertThat(boundedTransport.manifest()).hasSize(11);
		assertThat(context.getBeansOfType(IcebreakerGateway.class)).hasSize(1).containsValue(gateway);
		assertThat(context.getBeansOfType(HttpIcebreakerGateway.class)).isEmpty();
		assertThat(gateway).isExactlyInstanceOf(PendingIcebreakerGateway.class);
		assertThat(gateway.enabled()).isFalse();
		assertThat(gateway.manifest()).isEmpty();
		assertThatThrownBy(() -> gateway.generate(1, UUID.randomUUID(), boundedTransport.manifest(),
			clock.instant().plus(Duration.ofMinutes(5))))
			.isInstanceOfSatisfying(IcebreakerGateway.GatewayException.class,
				exception -> assertThat(exception.retryable()).isFalse());

		JsonNode first = newReadyUser();
		JsonNode second = newReadyUser();
		ChatRoom room = createRoom(userIdOf(first), userIdOf(second));
		String path = "/api/chat-rooms/" + room.getId() + "/icebreakers";
		perform(post(path).header("Idempotency-Key", UUID.randomUUID()), first, null)
			.andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"));
		assertThat(processor.process(room.getId())).isTrue();
		String response = perform(get(path), second, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.source").value("GENERAL_FALLBACK"))
			.andReturn().getResponse().getContentAsString();
		JsonNode result = jsonMapper.readTree(response);
		assertThat(result.path("questions").size()).isEqualTo(3);
		for (JsonNode question : result.path("questions")) {
			assertThat(question.asString()).isNotBlank();
		}
		assertThat(REQUESTS.get()).isZero();
	}

	private JsonNode newReadyUser() throws Exception {
		JsonNode tokens = login("google", newSocialId());
		completeOnboarding(tokens);
		return tokens;
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
