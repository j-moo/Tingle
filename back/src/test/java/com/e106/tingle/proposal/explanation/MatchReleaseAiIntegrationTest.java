package com.e106.tingle.proposal.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.encounter.EncounterEvaluationGateway.Input;
import com.e106.tingle.encounter.EncounterEvaluationGateway.ParticipantInput;
import com.e106.tingle.encounter.EncounterEvaluationWorker;
import com.e106.tingle.encounter.EncounterReceipts;
import com.e106.tingle.encounter.StoredEncounterInputLookup;
import com.e106.tingle.encounter.peer.KeyringVerdictEvaluator;
import com.e106.tingle.encounter.peer.Ter2BusinessReleaseResolver;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationStore;
import com.e106.tingle.settings.snapshot.SettingsSnapshotJson;
import com.e106.tingle.survey.SurveyCatalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 BE JWT/서비스 JWT/Release 로더/과거 근거 검증기/FastAPI/JSONB/GET 연결.
 * 외부 GMS만 대역이다. 가입 및 이미 검증된 양측 기기 원본 저장소는 합성 fixture로 준비한다.
 * TER2 수신 시 서명 검증 자체는 Ter2BusinessPromotionTest가 검증하며 이 테스트의 주장이 아니다.
 */
@Tag("ai-explanation-http")
@SpringBootTest(properties = {
	"app.match-explanations.scheduling-enabled=false",
	"app.explanations.descriptors.f73ba248-c5f7-531e-938f-f60962bcc57b=match-release-synthetic/ai-descriptor.json"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MatchReleaseAiIntegrationTest.Verification.class})
class MatchReleaseAiIntegrationTest extends IntegrationTestSupport {

	private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
	private static final UUID RELEASE = UUID.fromString("f73ba248-c5f7-531e-938f-f60962bcc57b");
	private static final String POLICY = "tingle-policy-v2";
	private static final String RULE = "r2-c-20261106";
	private static Path temporary;
	private static Process process;
	private static String base;
	@Autowired
	private DataSource dataSource;
	@Autowired
	private ProposalRepository proposals;
	@Autowired
	private MatchingParticipantRepository participants;
	@Autowired
	private HistoricalEvaluationStore history;
	@Autowired
	private KeyringVerdictEvaluator evaluator;
	@Autowired
	private Ter2BusinessReleaseResolver resolver;
	@Autowired
	private ExplanationArtifactCatalog catalog;
	private JsonNode tokens;
	private long proposalId;
	private JsonNode original;

	@TestConfiguration(proxyBeanMethods = false)
	static class Verification {
		@Bean
		Ter2BusinessReleaseResolver syntheticReleaseResolver(JdbcTemplate jdbc, JsonMapper mapper) {
			return new Ter2BusinessReleaseResolver(jdbc, mapper);
		}

		@Bean
		KeyringVerdictEvaluator realHistoricalVerifier(JdbcTemplate jdbc, JsonMapper mapper,
			StoredEncounterInputLookup inputs, Ter2BusinessReleaseResolver releases) {
			return new KeyringVerdictEvaluator(jdbc, mapper, new EncounterReceipts(jdbc, mapper), inputs, releases);
		}
	}

	@DynamicPropertySource
	static void loopback(DynamicPropertyRegistry properties) throws Exception {
		temporary = Files.createTempDirectory("tingle-match-release-synthetic-");
		var generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		var pair = generator.generateKeyPair();
		writePem("private.pem", "PRIVATE KEY", pair.getPrivate().getEncoded());
		writePem("public.pem", "PUBLIC KEY", pair.getPublic().getEncoded());
		Files.writeString(temporary.resolve("mode.txt"), "off");
		int port;
		try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
			port = socket.getLocalPort();
		}
		base = "http://127.0.0.1:" + port;
		Path ai = Path.of("../ai-server").toAbsolutePath().normalize();
		Path descriptor = Path.of("src/test/resources/match-release-synthetic/ai-descriptor.json").toAbsolutePath();
		String python = System.getenv("EXPLANATION_TEST_PYTHON");
		assertThat(python).isNotBlank();
		var command = new ProcessBuilder(python, ai.resolve("tests/match_release_http_fixture_server.py").toString(),
			"--port", Integer.toString(port), "--public-key", temporary.resolve("public.pem").toString(),
			"--descriptor", descriptor.toString(), "--mode-file", temporary.resolve("mode.txt").toString())
			.directory(ai.toFile()).redirectErrorStream(true)
			.redirectOutput(temporary.resolve("ai.log").toFile());
		command.environment().put("PYTHONPATH", ai.toString());
		command.environment().put("PYTHONDONTWRITEBYTECODE", "1");
		process = command.start();
		try {
			long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
			while (System.nanoTime() < deadline && process.isAlive()) {
				try {
					if (health().path("status").asString().equals("ok")) {
						break;
					}
				} catch (Exception exception) {
					// loopback 기동 대기. 원문이나 인증 정보를 출력하지 않는다.
				}
				Thread.sleep(100);
			}
			assertThat(health().path("status").asString()).isEqualTo("ok");
		} catch (Exception | AssertionError exception) {
			stop();
			throw exception;
		}
		properties.add("app.ai.service.enabled", () -> true);
		properties.add("app.ai.service.environment", () -> "test");
		properties.add("app.ai.service.key-id", () -> "synthetic-key");
		properties.add("app.ai.service.private-key-file", () -> temporary.resolve("private.pem").toString());
		properties.add("app.ai.service.base-url", () -> base);
	}

	@AfterAll
	static void stop() throws Exception {
		if (process != null) {
			List<ProcessHandle> children = process.descendants().toList();
			children.forEach(ProcessHandle::destroy);
			process.destroy();
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
			}
			for (ProcessHandle child : children) {
				if (child.isAlive()) {
					child.destroyForcibly();
				}
				child.onExit().get(5, TimeUnit.SECONDS);
			}
		}
		if (temporary != null) {
			// 이 테스트가 만든 정확한 임시 키 파일만 지운다.
			Files.deleteIfExists(temporary.resolve("private.pem"));
			Files.deleteIfExists(temporary.resolve("public.pem"));
		}
	}

	@BeforeEach
	void syntheticVerifiedHistory() throws Exception {
		given(clock.instant()).willReturn(NOW);
		if (jdbc.queryForObject("select to_regclass('keyring_peer_ownership') is null", Boolean.class)) {
			new ResourceDatabasePopulator(new ClassPathResource("database/keyring-peer-ownership-270.sql"),
				new ClassPathResource("database/keyring-peer-encounter-270.sql")).execute(dataSource);
		}
		tokens = login("kakao", newSocialId());
		JsonNode peer = login("kakao", newSocialId());
		completeOnboarding(tokens);
		completeOnboarding(peer);
		long first = userIdOf(tokens);
		long second = userIdOf(peer);
		seedRelease(first, second);
		Instant metAt = NOW.minusSeconds(60);
		UUID event = UUID.randomUUID();
		ParticipantInput left = acceptedOriginal(first, second, event, metAt);
		ParticipantInput right = acceptedOriginal(second, first, event, metAt);
		MatchProposal proposal = proposals.saveAndFlush(MatchProposal.proposed(first, second, NOW));
		proposalId = proposal.getId();
		participants.saveAllAndFlush(List.of(new MatchingParticipant(proposalId, first),
			new MatchingParticipant(proposalId, second)));
		jdbc.update("update matching set met_at=?,policy_version=? where id=?", Timestamp.from(metAt),
			POLICY, proposalId);
		Input input = new Input(proposalId, UUID.randomUUID(), first, second, metAt, NOW.plusSeconds(600),
			NOW.plusSeconds(600), POLICY, RELEASE, RULE, left, right);
		String inputHash = new EncounterReceipts(jdbc, jsonMapper).canonicalHash(input);
		evaluator.requireApprovedRelease(input, null, null);
		var result = evaluator.evaluate(input, inputHash);
		jdbc.update("update matching set evaluation=cast(? as jsonb) where id=?",
			toJson(result.evaluation()), proposalId);
		var work = new EncounterEvaluationWorker.Work(input.generationId(), UUID.randomUUID(), inputHash,
			EncounterEvaluationWorker.State.SUCCEEDED, 1, null, null, input);
		jdbc.update("update matching_participant set request_state=jsonb_build_object(cast(? as text),"
			+ "cast(? as text)) where matching_id=? and user_id=?", EncounterEvaluationWorker.WORKFLOW_KEY,
			toJson(work), proposalId, first);
		history.store(result.historicalEvidence());
		assertThat(history.find(proposalId)).contains(result.historicalEvidence());
		assertThat(catalog.supports(jsonMapper.valueToTree(result.historicalEvidence().manifest()))).isTrue();
		original = storedOriginal();
	}

	@ParameterizedTest
	@CsvSource({"off,RULE_FALLBACK,0,0", "generated,GENERATED,1,1", "timeout,RULE_FALLBACK,1,0",
		"review-rejected,RULE_FALLBACK,1,1", "unsafe,RULE_FALLBACK,1,0"})
	void signedAiResultPersistsAndGetsWithoutChangingOriginalEvidence(String mode, String source,
		int generations, int verifications) throws Exception {
		Files.writeString(temporary.resolve("mode.txt"), mode);
		JsonNode before = health().at("/counters/" + mode);
		String path = "/api/match-proposals/" + proposalId;
		mockMvc.perform(get(path + "/explanation")).andExpect(status().isUnauthorized());
		perform(post(path + "/explanations").header("Idempotency-Key", UUID.randomUUID()), tokens, null)
			.andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
		JsonNode response = jsonMapper.readTree(perform(get(path + "/explanation"), tokens, null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.source").value(source))
			.andExpect(jsonPath("$.evidenceKeys").isEmpty())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
		assertThat(response.path("text").asString()).isNotBlank();
		if ("GENERATED".equals(source)) {
			assertThat(response.path("promptVersion").asString()).matches("[a-f0-9]{64}");
		} else {
			assertThat(response.path("promptVersion").isNull()).isTrue();
		}
		String stored = jdbc.queryForObject("select evaluation::text from matching where id=?", String.class,
			proposalId);
		assertThat(stored).contains(response.path("text").asString(), source);
		assertThat(storedOriginal()).isEqualTo(original);
		JsonNode after = health().at("/counters/" + mode);
		assertThat(after.path("generation").asInt() - before.path("generation").asInt()).isEqualTo(generations);
		assertThat(after.path("verification").asInt() - before.path("verification").asInt())
			.isEqualTo(verifications);
		// 같은 결과는 활성화 상태가 바뀌어도 재생성하지 않는다.
		Files.writeString(temporary.resolve("mode.txt"), "off");
		perform(post(path + "/explanations").header("Idempotency-Key", UUID.randomUUID()), tokens, null)
			.andExpect(status().isAccepted());
		assertThat(health().at("/counters/" + mode)).isEqualTo(after);
		JsonNode outsider = login("kakao", newSocialId());
		completeOnboarding(outsider);
		perform(get(path + "/explanation"), outsider, null).andExpect(status().isNotFound());
	}

	@Test
	void retiredHistoricalProofBlocksGenerationBeforeAnyProviderCall() throws Exception {
		Files.writeString(temporary.resolve("mode.txt"), "generated");
		JsonNode before = health().at("/counters/generated");
		long user = userIdOf(tokens);
		jdbc.update("update keyring_peer_encounter set retired=true where owner_user_id=?", user);
		assertThat(history.find(proposalId)).isEmpty();
		String path = "/api/match-proposals/" + proposalId;
		perform(post(path + "/explanations").header("Idempotency-Key", UUID.randomUUID()), tokens, null)
			.andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("FAILED"));
		perform(get(path + "/explanation"), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.text").isEmpty());
		assertThat(health().at("/counters/generated")).isEqualTo(before);
		assertThat(storedOriginal()).isEqualTo(original);
	}

	private void seedRelease(long author, long reviewer) throws Exception {
		JsonNode manifest = jsonMapper.readTree(resource("manifest.json"));
		for (JsonNode item : jsonMapper.readTree(resource("documents.json"))) {
			jdbc.update("insert into rule_artifact(id,kind,version,schema_version,revision,document,content_hash,"
				+ "created_by,created_at,published_at) values (?,?,?,?,?,cast(? as jsonb),?,?,?,?) "
				+ "on conflict(id) do nothing", UUID.fromString(item.path("id").asString()),
				item.path("kind").asString(), item.path("version").asString(), item.path("schemaVersion").asString(),
				item.path("revision").asInt(), item.path("canonicalDocument").asString(),
				item.path("contentHash").asString(), author, Timestamp.from(NOW.minusSeconds(180)),
				Timestamp.from(NOW.minusSeconds(180)));
		}
		List<String> ids = jsonMapper.readTree(resource("be-metadata.json")).path("artifacts").valueStream()
			.map(item -> item.path("id").asString()).toList();
		String hash = manifest.path("contentHash").asString();
		jdbc.update("insert into rule_release(id,name,engine_version,artifact_ids,created_by,created_at,"
			+ "validation_id,validation_hash,validation_passed,approval_id,approval_hash,approved_by,approved_at,"
			+ "published_by,published_at) values (?,'SYNTHETIC ONLY','tingle-rules-1',cast(? as jsonb),"
			+ "1,?,?,?,true,?,?,?, ?,?,?) on conflict(id) do nothing", RELEASE, toJson(ids),
			Timestamp.from(NOW.minusSeconds(180)), UUID.randomUUID(), hash, UUID.randomUUID(), hash, reviewer,
			Timestamp.from(NOW.minusSeconds(180)), author, Timestamp.from(NOW.minusSeconds(180)));
		ReflectionTestUtils.setField(resolver, "approved", true);
		ReflectionTestUtils.setField(resolver, "configuredRelease", RELEASE.toString());
		ReflectionTestUtils.setField(resolver, "configuredHash", hash);
		assertThat(resolver.resolve(POLICY, RULE, NOW.minusSeconds(60))).isEqualTo(RELEASE);
	}

	private ParticipantInput acceptedOriginal(long user, long peer, UUID event, Instant metAt) throws Exception {
		long device = jdbc.queryForObject("select id from keyring where owner_user_id=?", Long.class, user);
		String serial = jdbc.queryForObject("select serial_number from keyring where id=?", String.class, device);
		long survey = jdbc.queryForObject("select id from survey where user_id=?", Long.class, user);
		jdbc.update("insert into survey_answer(survey_id,question_id,answer_value,answer_type) values "
			+ "(?,'Q034','3','NUMBER'),(?,'Q069','3','NUMBER')", survey, survey);
		SettingsSnapshotJson snapshotJson = new SettingsSnapshotJson();
		String canonical = snapshotJson.canonicalize("{\"analysis\":{\"surveyId\":" + survey
			+ ",\"surveyVersion\":\"SIS-Q-1.1-100\"}}");
		String hash = snapshotJson.hash(canonical);
		UUID generation = UUID.randomUUID();
		UUID run = UUID.randomUUID();
		UUID submission = UUID.randomUUID();
		JsonNode vectors;
		try (var stream = new ClassPathResource("keyring/app-flow-v3-vectors.json").getInputStream()) {
			vectors = jsonMapper.readTree(stream).path("vectors");
		}
		byte[] payload = Base64.getUrlDecoder().decode(vectors.at("/bundle/base64url").asString());
		byte[] raw = Base64.getUrlDecoder().decode(vectors.at("/encounter/base64url").asString());
		String proofHash = SurveyCatalog.sha256(raw);
		Timestamp then = Timestamp.from(metAt.minusSeconds(1));
		jdbc.update("update keyring set owner_generation=?,credential_key_id='synthetic-key',"
			+ "credential_public_key='synthetic-public' where id=?", generation, device);
		jdbc.update("insert into keyring_peer_ownership values (?,?,1,?,?,?,'synthetic-key','synthetic-public',?,null)",
			device, generation, user, UUID.randomUUID(), serial, then);
		jdbc.update("insert into settings_snapshot(user_id,settings_version,input_format,input_json,input_hash,"
			+ "captured_at) values (?,900,'settings-source-evidence-v1',?,?,?)", user, canonical, hash, then);
		jdbc.update("insert into keyring_sync_run(sync_run_id,keyring_id,owner_user_id,owner_generation,"
			+ "ownership_epoch,settings_version,state,payload_format,protocol_version,credential_key_id,payload,"
			+ "payload_hash,nonce,created_at,acked_at,ack_proof_hash,ack_request_key,request_key,revision) "
			+ "values (?,?,?,?,1,900,'ACKED','tingle-keyring-settings-v3','tingle-device-proof-v2',"
			+ "'synthetic-key',?,?,'synthetic',?,?,?, ?,?,0)", run, device, user, generation, payload,
			SurveyCatalog.sha256(payload), then, then, "a".repeat(64), UUID.randomUUID(), UUID.randomUUID());
		jdbc.update("insert into keyring_peer_encounter(receipt_id,owner_user_id,peer_user_id,device_id,"
			+ "owner_generation,ownership_epoch,event_id,submission_id,received_at,occurred_at,expires_at,"
			+ "encounter_status,raw_payload,envelope_hash) values (?,?,?,?,?,1,?,?,?,?,?,'VERIFIED_BOTH',?,?)",
			UUID.randomUUID(), user, peer, device, generation, event, submission, Timestamp.from(NOW),
			Timestamp.from(metAt), Timestamp.from(NOW.plusSeconds(600)), raw, proofHash);
		return new ParticipantInput(user, device, submission, event, "c".repeat(64), run, 900, generation, hash,
			proofHash);
	}

	private JsonNode storedOriginal() {
		return jsonMapper.readTree(jdbc.queryForObject("select (evaluation-'matchExplanations')::text "
			+ "from matching where id=?", String.class, proposalId));
	}

	private byte[] resource(String name) throws Exception {
		try (var input = new ClassPathResource("match-release-synthetic/" + name).getInputStream()) {
			return input.readAllBytes();
		}
	}

	private static JsonNode health() throws Exception {
		var request = HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(1)).GET().build();
		return JsonMapper.builder().build().readTree(HttpClient.newHttpClient()
			.send(request, HttpResponse.BodyHandlers.ofString()).body());
	}

	private static void writePem(String name, String label, byte[] bytes) throws Exception {
		String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
		Files.writeString(temporary.resolve(name), "-----BEGIN " + label + "-----\n" + body
			+ "\n-----END " + label + "-----\n");
	}
}
