package com.e106.tingle.risk;

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
import com.e106.tingle.risk.RiskAnalysisTypes.Audience;
import com.e106.tingle.risk.RiskAnalysisTypes.Category;
import com.e106.tingle.risk.RiskAnalysisTypes.Finding;
import com.e106.tingle.risk.RiskAnalysisTypes.Input;
import com.e106.tingle.risk.RiskAnalysisTypes.Kind;
import com.e106.tingle.risk.RiskAnalysisTypes.Manifest;
import com.e106.tingle.risk.RiskAnalysisTypes.Message;
import com.e106.tingle.risk.RiskAnalysisTypes.NoticeType;
import com.e106.tingle.risk.RiskAnalysisTypes.Request;
import com.e106.tingle.risk.RiskAnalysisTypes.Verdict;

import tools.jackson.databind.json.JsonMapper;

/** 실제 BE 서명기·FastAPI 검증기의 loopback 검증. 모델과 버전 파일은 모두 가상 입력이다. */
@Tag("ai-risk-http")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(20)
class RiskAiHttpSmokeTest {

	private final JsonMapper json = JsonMapper.builder().build();
	private final HttpClient probe = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
		.version(HttpClient.Version.HTTP_1_1).build();
	@TempDir
	static Path temporary;
	private Process process;
	private Manifest manifest;
	private Path privateKey;
	private String base;
	private HttpRiskAnalysisGateway gateway;

	@BeforeAll
	@Timeout(40)
	void startSyntheticAiOnlyOnLoopback() throws Exception {
		String python = System.getenv("RISK_TEST_PYTHON");
		assertThat(python).as("RISK_TEST_PYTHON must name Python with the AI test dependencies").isNotBlank();
		KeyPair pair = pair();
		privateKey = pem("service-private.pem", "PRIVATE KEY", pair.getPrivate().getEncoded());
		Path publicKey = pem("service-public.pem", "PUBLIC KEY", pair.getPublic().getEncoded());
		byte[] artifact = "{\"synthetic\":true}".getBytes(StandardCharsets.UTF_8);
		Files.write(temporary.resolve("artifact.json"), artifact);
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact));
		String version = "synthetic-v1";
		manifest = new Manifest(UUID.randomUUID(), version, version, version, version, version, version,
			version, version, version, hash);
		Path descriptor = temporary.resolve("descriptor.json");
		Files.writeString(descriptor, json.writeValueAsString(Map.of("manifest", manifest,
			"artifact", "artifact.json")));
		assertThat(new RiskManifestCatalog(new RiskReleaseProperties(descriptor.toString()), json)
			.supports(manifest)).isTrue();
		int port = availablePort();
		base = "http://127.0.0.1:" + port;
		Path ai = Path.of("..", "ai-server").toAbsolutePath().normalize();
		ProcessBuilder command = new ProcessBuilder(python, ai.resolve("tests/risk_http_fixture_server.py").toString(),
			"--port", Integer.toString(port), "--public-key", publicKey.toString(),
			"--descriptor", descriptor.toString()).directory(ai.toFile()).redirectErrorStream(true)
			.redirectOutput(temporary.resolve("synthetic-ai-output.log").toFile());
		command.environment().put("PYTHONPATH", ai.toString());
		command.environment().put("PYTHONDONTWRITEBYTECODE", "1");
		try {
			process = command.start();
			awaitHealth();
			AiServiceProperties properties = properties("test", privateKey);
			AiInternalClient client = new AiInternalClient(properties, issuer("test", privateKey), json);
			// 정확히 100을 반환하는 합성 tokenizer이며 실제 모델의 토큰 수 검증은 아니다.
			gateway = new HttpRiskAnalysisGateway(client, input -> 100);
		} catch (Exception | AssertionError exception) {
			stopSyntheticAi();
			throw exception;
		}
	}

	@AfterAll
	void stopSyntheticAi() throws InterruptedException {
		if (process != null) {
			process.destroy();
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
			}
		}
	}

	@Test
	void signedBeRequestPassesActualAiAuthenticationAndReturnsNormal() {
		Input input = input("가상 대화: 오늘은 공원에서 산책할까요?");
		Verdict verdict = analyze(input);
		assertThat(verdict.kind()).isEqualTo(Kind.NORMAL);
		assertThat(verdict.findings()).isEmpty();
	}

	@Test
	void signedBeRequestReturnsRiskWithTheExplicitRecipientNoticeMapping() {
		Input input = input("가상 대화: 확인을 위해 먼저 송금해 주세요");
		Verdict verdict = analyze(input);
		assertThat(verdict.kind()).isEqualTo(Kind.RISK);
		assertThat(verdict.findings()).containsExactly(new Finding(1, Category.FRAUD,
			NoticeType.MONEY_REQUEST, Audience.RECIPIENT));
	}

	@Test
	void aiRejectsARealBeSignatureForAnotherEnvironment() throws Exception {
		assertRejected(issuer("dev", privateKey));
	}

	@Test
	void aiRejectsAnUntrustedSigningKeyEvenWithTheRegisteredKid() throws Exception {
		Path wrong = pem("untrusted-private.pem", "PRIVATE KEY", pair().getPrivate().getEncoded());
		assertRejected(issuer("test", wrong));
	}

	private Verdict analyze(Input input) {
		Request request = new Request(UUID.randomUUID(), 7, manifest);
		return new RiskVerdicts().validate(gateway.analyze(request, input), input);
	}

	private void assertRejected(AiServiceTokenProvider issuer) throws Exception {
		Input input = input("가상 대화: 인증 실패 검증용");
		var body = new HttpRiskAnalysisGateway.WireRequest(UUID.randomUUID(), 7, manifest, input.messages());
		UUID requestId = UUID.randomUUID();
		HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/internal/ai/risk-detections"))
			.timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
			.header("Authorization", "Bearer " + issuer.issue()).header("X-Request-Id", requestId.toString())
			.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build();
		HttpResponse<String> response = probe.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("ACCESS_TOKEN_INVALID");
		assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
		assertThat(response.headers().firstValue("X-Request-Id")).contains(requestId.toString());
	}

	private Input input(String text) {
		return new Input(List.of(new Message(1, "A", text, true, false)), manifest);
	}

	private AiServiceProperties properties(String environment, Path file) {
		return new AiServiceProperties(true, environment, "synthetic-key", file.toString(), base);
	}

	private AiServiceTokenProvider issuer(String environment, Path file) {
		return new AiServiceTokenProvider(properties(environment, file), Clock.systemUTC(), json);
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
				// loopback 기동 중 연결 거부만 기다리며 원본 로그를 테스트 출력에 복사하지 않는다.
			}
			Thread.sleep(100);
		}
		throw new IllegalStateException("Synthetic AI did not become healthy; output is in a temporary file");
	}

	private int availablePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
			return socket.getLocalPort();
		}
	}

	private KeyPair pair() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		return generator.generateKeyPair();
	}

	private Path pem(String name, String label, byte[] value) throws IOException {
		String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(value);
		Path file = temporary.resolve(name);
		Files.writeString(file, "-----BEGIN " + label + "-----\n" + encoded + "\n-----END " + label + "-----\n");
		return file;
	}
}
