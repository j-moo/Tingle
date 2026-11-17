package com.e106.tingle.proposal.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.e106.tingle.global.ai.AiInternalClient;
import com.e106.tingle.global.ai.AiServiceProperties;
import com.e106.tingle.global.ai.AiServiceTokenProvider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 임시 키·합성 과거 근거·원본 파일로 실제 Java→loopback FastAPI 규칙 호출을 검사한다. */
@Tag("ai-explanation-http")
@Timeout(45)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExplanationAiHttpSmokeTest {

	@TempDir
	static Path temporary;
	private final JsonMapper json = JsonMapper.builder().build();
	private final HttpClient probe = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
		.version(HttpClient.Version.HTTP_1_1).build();
	private Process process;
	private String base;
	private Path privateKey;
	private JsonNode manifest;
	private HttpRuleExplanationTransport transport;

	@BeforeAll
	void startSyntheticAi() throws Exception {
		String python = System.getenv("EXPLANATION_TEST_PYTHON");
		assertThat(python).as("EXPLANATION_TEST_PYTHON must have the AI test dependencies").isNotBlank();
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair pair = generator.generateKeyPair();
		privateKey = pem("service-private.pem", "PRIVATE KEY", pair.getPrivate().getEncoded());
		Path publicKey = pem("service-public.pem", "PUBLIC KEY", pair.getPublic().getEncoded());
		byte[] artifact = "{\"synthetic\":true}".getBytes(StandardCharsets.UTF_8);
		Files.write(temporary.resolve("artifact.json"), artifact);
		Map<String, Object> values = new HashMap<>();
		for (String field : ExplanationArtifactCatalog.MANIFEST_FIELDS) {
			values.put(field, "synthetic-v1");
		}
		values.put("releaseId", UUID.randomUUID().toString());
		values.put("contentHash", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact)));
		manifest = json.valueToTree(values);
		Path descriptor = temporary.resolve("descriptor.json");
		Files.writeString(descriptor, json.writeValueAsString(Map.of("manifest", values, "artifact", "artifact.json")));
		int port;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
			port = socket.getLocalPort();
		}
		base = "http://127.0.0.1:" + port;
		Path ai = Path.of("..", "ai-server").toAbsolutePath().normalize();
		ProcessBuilder command = new ProcessBuilder(python,
			ai.resolve("tests/explanation_http_fixture_server.py").toString(),
			"--port", Integer.toString(port), "--public-key", publicKey.toString(),
			"--descriptor", descriptor.toString())
			.directory(ai.toFile()).redirectErrorStream(true)
			.redirectOutput(temporary.resolve("synthetic-ai-output.log").toFile());
		command.environment().put("PYTHONPATH", ai.toString());
		command.environment().put("PYTHONDONTWRITEBYTECODE", "1");
		try {
			process = command.start();
			awaitHealth();
			AiServiceProperties properties = properties("test");
			transport = new HttpRuleExplanationTransport(new AiInternalClient(properties, issuer("test"), json));
		} catch (Exception | AssertionError exception) {
			stopSyntheticAi();
			throw exception;
		}
	}

	@AfterAll
	void stopSyntheticAi() throws InterruptedException {
		if (process != null) {
			// Windows venv의 launcher 자식도 종료해야 임시 로그 파일 잠금이 풀린다.
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
				try {
					child.onExit().get(5, TimeUnit.SECONDS);
				} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
					throw new IllegalStateException("Synthetic AI child did not stop", exception);
				}
			}
		}
	}

	@Test
	void signedRequestReturnsOnlyRuleExplanationFromTheApprovedPastBundle() throws Exception {
		HttpRequest raw = HttpRequest.newBuilder(URI.create(base + "/internal/ai/explanations"))
			.timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
			.header("Authorization", "Bearer " + issuer("test").issue())
			.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request()), StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> response = probe.send(raw, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("Synthetic response: %s", response.body()).isEqualTo(200);
		var result = transport.explain(request()).orElseThrow();
		assertThat(result.status()).isEqualTo("SUCCEEDED");
		assertThat(result.source()).isEqualTo("RULE_FALLBACK");
		assertThat(result.text()).isNotBlank();
		assertThat(result.evidenceKeys()).isEmpty();
		assertThat(result.promptVersion()).isNull();
	}

	@Test
	void unregisteredHistoricalReleaseCannotProduceAnExplanation() {
		RuleExplanationTransport.Request valid = request();
		ObjectNode other = (ObjectNode)manifest.deepCopy();
		other.put("releaseId", UUID.randomUUID().toString());
		assertThat(transport.explain(new RuleExplanationTransport.Request(valid.attemptId(), 17, other,
			valid.contextBundle()))).isEmpty();
	}

	@Test
	void invertedOneWayFactCannotBeRepairedByItsDirectionLabel() {
		RuleExplanationTransport.Request valid = request();
		ObjectNode bundle = (ObjectNode)valid.contextBundle().deepCopy();
		((ObjectNode)bundle.path("context").path("evidence").get(0).path("evidence"))
			.put("fact", "A가 바라는 연락 빈도에 B가 잘 맞아요.");
		assertThat(transport.explain(new RuleExplanationTransport.Request(valid.attemptId(), 17, manifest,
			bundle))).isEmpty();
	}

	@Test
	void anUnapprovedTopicCannotBeSentAsPublicEvidence() {
		RuleExplanationTransport.Request valid = request();
		ObjectNode bundle = (ObjectNode)valid.contextBundle().deepCopy();
		((ObjectNode)bundle.path("context").path("evidence").get(0).path("evidence")).put("topic", "religion");
		assertThat(transport.explain(new RuleExplanationTransport.Request(valid.attemptId(), 17, manifest,
			bundle))).isEmpty();
	}

	@Test
	void realSignatureForAnotherEnvironmentPreservesTheAuthenticationError() throws Exception {
		UUID requestId = UUID.randomUUID();
		HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/internal/ai/explanations"))
			.timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
			.header("Authorization", "Bearer " + issuer("dev").issue()).header("X-Request-Id", requestId.toString())
			.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request()), StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> response = probe.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("ACCESS_TOKEN_INVALID");
		assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
		assertThat(response.headers().firstValue("X-Request-Id")).contains(requestId.toString());
	}

	private RuleExplanationTransport.Request request() {
		Map<String, Object> reference = Map.of("encounter_id", 17, "evaluation_ref", "b".repeat(64),
			"version_ref", manifest.path("releaseId").asString());
		Map<String, Object> evidence = Map.of("id", "e1", "kind", "ONE_WAY_PREFERENCE_FIT", "topic", "contact",
			"direction", "A_TO_B", "fact", "B가 바라는 연락 빈도에 A가 잘 맞아요.");
		JsonNode bundle = json.valueToTree(Map.of(
			"context", Map.of("reference", reference, "viewer", "A", "conflicting_source_refs", List.of(),
				"evidence", List.of(Map.of("reference", reference, "source_ref", "survey:SIS-Q-1.1-100:Q034",
					"evidence", evidence))),
			"scope", Map.of("reference", reference, "viewer", "A", "version", "synthetic-approved-scope",
				"model_evidence_ids", List.of(), "display_evidence_ids", List.of("e1"))));
		return new RuleExplanationTransport.Request(UUID.randomUUID(), 17, manifest, bundle);
	}

	private AiServiceProperties properties(String environment) {
		return new AiServiceProperties(environment, "synthetic-key", privateKey.toString(), base);
	}

	private AiServiceTokenProvider issuer(String environment) {
		return new AiServiceTokenProvider(properties(environment), Clock.systemUTC(), json);
	}

	private void awaitHealth() throws Exception {
		long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (System.nanoTime() < until && process.isAlive()) {
			try {
				HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/health"))
					.timeout(Duration.ofSeconds(1)).GET().build();
				if (probe.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
					return;
				}
			} catch (IOException exception) {
				// loopback 기동 대기만 허용하며 원문 출력을 복사하지 않는다.
			}
			Thread.sleep(100);
		}
		throw new IllegalStateException("Synthetic explanation server did not become healthy");
	}

	private Path pem(String name, String label, byte[] value) throws IOException {
		String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(value);
		Path file = temporary.resolve(name);
		Files.writeString(file, "-----BEGIN " + label + "-----\n" + encoded + "\n-----END " + label + "-----\n");
		return file;
	}
}
