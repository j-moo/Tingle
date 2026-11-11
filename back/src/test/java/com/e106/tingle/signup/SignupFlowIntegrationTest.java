package com.e106.tingle.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.auth.SocialProvider;
import com.e106.tingle.auth.SocialVerifier.SocialAccount;
import com.e106.tingle.keyring.KeyringPayload;
import com.e106.tingle.keyring.KeyringPayloadProvider;
import com.e106.tingle.keyring.proof.KeyringProofTranscript;

import tools.jackson.databind.JsonNode;

/**
 * 가입 전체 흐름을 실제 API 순서대로 걷는다: 로그인 → 약관(이메일 저장) → 키링 → 프로필 → 설문·분석 → 희망 조건 → 동기화 → READY.
 * 단계마다 가입 단계 조회로 다음 단계를 확인한다. 다른 영역 변경이 가입을 깨면 여기서 바로 드러난다.
 * 바깥 시스템만 대신한다: 소셜 서버(SocialVerifier), AI 서버(PersonalAnalysisClient), 키링 기기(Ed25519 키·설정 payload).
 */
@SpringBootTest(properties = {"app.withdrawal.enabled=true",
	"app.withdrawal.identity-secret=identity-test-secret-is-at-least-thirty-two-bytes-long",
	"app.withdrawal.receipt-secret=receipt-test-secret-is-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class SignupFlowIntegrationTest extends IntegrationTestSupport {

	private static final String SURVEY_VERSION = "SIS-Q-1.1-100";
	private static final String PROOF_PROTOCOL = "tingle-device-proof-v1";

	// 기기에 보낼 설정 묶음은 기기 쪽 경계다. 키링 동기화 테스트와 같은 합성 payload를 쓴다.
	@MockitoBean
	private KeyringPayloadProvider payloadProvider;

	@Autowired
	private ApplicationContext context;

	@Test
	void walksSignupToReadyThenEndsOldDeviceSessionAndRestrictsRejoinAfterWithdrawal() throws Exception {
		String socialId = newSocialId();

		// 1. 로그인: 새 회원은 약관 단계부터
		JsonNode tokens = signIn(socialId, "first@example.com");
		assertThat(tokens.path("onboardingStatus").asString()).isEqualTo("AGREEMENT_REQUIRED");
		long userId = userIdOf(tokens);
		step(tokens, "AGREEMENT_REQUIRED");
		assertThat(email(userId)).isNull();

		// 2. 약관 동의: 개인정보 동의와 함께 로그인 때 받은 이메일을 저장하고, 기본 정책대로 키링 단계로
		agree(tokens).andExpect(status().isOk()).andExpect(jsonPath("$.onboardingStatus").value("KEYRING_REQUIRED"));
		assertThat(email(userId)).isEqualTo("first@example.com");
		step(tokens, "KEYRING_REQUIRED").andExpect(jsonPath("$.keyringRequirement").value("REQUIRED"));

		// 3. 키링 등록: 서버 challenge 에 기기 키로 서명
		Device device = registerKeyring(tokens);
		step(tokens, "PROFILE_REQUIRED");

		// 4. 프로필 저장 → 등록 완료
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
		step(tokens, "PROFILE_REQUIRED");
		completeProfile(tokens).andExpect(status().isOk());
		step(tokens, "SURVEY_REQUIRED");

		// 5. 설문 100문항 응답 → 완료 → 분석 요청 → (가짜 AI) 분석 성공 적용
		long sessionId = answerSurvey(tokens);
		step(tokens, "SURVEY_REQUIRED");
		analyze(tokens, sessionId);
		step(tokens, "PREFERENCES_REQUIRED");

		// 6. 희망 조건 저장
		String preferences = me(tokens, "/preferences");
		String etag = perform(get(preferences), tokens, null).andExpect(status().isOk())
			.andReturn().getResponse().getHeader("ETag");
		perform(put(preferences).header("If-Match", etag), tokens,
			"{\"desiredAgeMode\":\"ANY\",\"desiredAgeMin\":null,\"desiredAgeMax\":null}")
			.andExpect(status().isOk());
		step(tokens, "SYNC_PENDING").andExpect(jsonPath("$.signupCompleted").value(false));

		// 7. 키링 동기화: 설정 발급 → 기기 서명 ACK → READY
		syncKeyring(tokens, device);
		step(tokens, "READY").andExpect(jsonPath("$.signupCompleted").value(true))
			.andExpect(jsonPath("$.matchingReady").value(true));

		// 8. 다른 기기에서 다시 로그인하면 이전 세션은 끝나고, 새 세션은 READY 로 이어진다
		JsonNode other = signIn(socialId, "first@example.com");
		assertThat(other.path("onboardingStatus").asString()).isEqualTo("READY");
		perform(get(me(tokens, "/onboarding")), tokens, null).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
		step(other, "READY");

		// 9. 탈퇴: 키링이 묶여 있으면 막히고, 기기 해제 뒤에는 완료된다. 탈퇴한 계정은 바로 다시 가입할 수 없다
		JsonNode prepared = jsonMapper.readTree(perform(post(me(other, "/withdrawals"))
			.header("Idempotency-Key", UUID.randomUUID()), other, null).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		withdraw(other, prepared).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WITHDRAWAL_KEYRING_REGISTERED"));
		// 기기 초기화(reset-runs)는 별도 기능 설정·기기 프로토콜이 필요해 KeyringResetIntegrationTest 가 맡는다.
		// 여기서는 초기화가 끝난 상태만 만든다.
		jdbc.update("update keyring set owner_user_id=null, owner_generation=null, state='UNBOUND', "
			+ "synced_settings_version=null, synced_at=null where id=?",
			device.id());
		withdraw(other, prepared).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
		assertThat(jdbc.queryForObject("select count(*) from users where id=?", Long.class, userId)).isZero();
		perform(get(me(other, "/onboarding")), other, null).andExpect(status().isUnauthorized());
		socialLogin(socialId, "first@example.com").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("REJOIN_RESTRICTED"))
			.andExpect(jsonPath("$.rejoinAvailableAt").exists());
	}

	// ---------- 단계 ----------

	private ResultActions step(JsonNode tokens, String expected) throws Exception {
		return perform(get(me(tokens, "/onboarding")), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.nextStep").value(expected));
	}

	private ResultActions socialLogin(String socialId, String email) throws Exception {
		given(socialVerifier.verify(eq(SocialProvider.KAKAO),
			argThat(request -> ("flow-" + socialId).equals(request.token()))))
			.willReturn(new SocialAccount(socialId, email));
		return mockMvc.perform(post("/api/auth/kakao").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"flow-" + socialId + "\"}"));
	}

	private JsonNode signIn(String socialId, String email) throws Exception {
		return jsonMapper.readTree(socialLogin(socialId, email).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	private Device registerKeyring(JsonNode tokens) throws Exception {
		// 공장에서 기기 키를 넣어 둔 미등록 키링(재고)
		KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		String serial = "flow-" + newSocialId();
		String keyId = "key-" + newSocialId();
		long id = jdbc.queryForObject("insert into keyring(serial_number,firmware_version,state,"
			+ "credential_key_id,credential_public_key) values (?,'flow-fw','UNBOUND',?,?) returning id",
			Long.class, serial, keyId, encode(keys.getPublic().getEncoded()));
		Device device = new Device(id, serial, keyId, keys);

		JsonNode challenge = jsonMapper.readTree(perform(post("/api/keyrings/registration-challenges")
			.header("Idempotency-Key", UUID.randomUUID()), tokens, null).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		byte[] transcript = KeyringProofTranscript.registration(keyId, serial,
			UUID.fromString(challenge.path("challengeId").asString()), challenge.path("nonce").asString(),
			Instant.parse(challenge.path("expiresAt").asString()).getEpochSecond()).bytes();
		perform(post("/api/keyrings").header("Idempotency-Key", UUID.randomUUID()), tokens,
			toJson(Map.of("challengeId", challenge.path("challengeId").asString(), "deviceSerial", serial,
				"proof", proof(device, transcript))))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.deviceId").value(id));
		return device;
	}

	private long answerSurvey(JsonNode tokens) throws Exception {
		JsonNode session = jsonMapper.readTree(perform(post("/api/survey-sessions"), tokens,
			toJson(Map.of("surveyVersion", SURVEY_VERSION))).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		long sessionId = session.path("sessionId").asLong();
		String base = "/api/survey-sessions/" + sessionId;
		for (JsonNode question : jsonMapper.readTree(perform(get(base + "/questionnaire"), tokens, null)
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("questions")) {
			String questionId = question.path("questionId").asString();
			// 모든 문항이 "모름"을 받는다. Q097(엄격 제외)·Q098(교류 범위)은 확인된 값이어야 분석 결과가 적용된다.
			Object value = switch (questionId) {
				case "Q097" -> "any";
				case "Q098" -> "some";
				default -> "unknown";
			};
			perform(put(base + "/answers/" + questionId), tokens, toJson(Map.of("value", value)))
				.andExpect(status().isOk());
		}
		perform(post(base + "/complete"), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"));
		return sessionId;
	}

	private void analyze(JsonNode tokens, long sessionId) throws Exception {
		given(personalAnalysisClient.ready()).willReturn(true);
		given(personalAnalysisClient.analyze(any(), anyString())).willReturn(jsonMapper.readTree(
			new ClassPathResource("personality/succeeded.json").getContentAsString(StandardCharsets.UTF_8)));
		perform(post("/api/survey-sessions/" + sessionId + "/analyses"), tokens, null)
			.andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"));
		// 운영에서는 주기 작업이 부른다(테스트 설정은 주기 작업 꺼짐). 실행기는 패키지 밖에 공개되지 않아 이름으로 부른다.
		ReflectionTestUtils.invokeMethod(context.getBean("personalAnalysisWorker"), "runDue");
	}

	private void syncKeyring(JsonNode tokens, Device device) throws Exception {
		given(payloadProvider.prepare(anyLong(), anyLong(), any(UUID.class))).willAnswer(invocation -> {
			long userId = invocation.getArgument(0);
			long version = jdbc.queryForObject("select settings_version from user_setting where user_id=?",
				Long.class, userId);
			long deviceId = invocation.getArgument(1);
			UUID generation = invocation.getArgument(2);
			return new KeyringPayload(userId, deviceId, generation, version, "synthetic-test-v1",
				("flow-payload-v" + version).getBytes(StandardCharsets.UTF_8));
		});
		String runs = "/api/keyrings/" + device.id() + "/sync-runs";
		JsonNode run = jsonMapper.readTree(perform(post(runs).header("Idempotency-Key", UUID.randomUUID()),
			tokens, null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		step(tokens, "SYNC_PENDING");
		UUID generation = UUID.fromString(run.path("ownerGeneration").asString());
		byte[] transcript = KeyringProofTranscript.stored(device.keyId(), device.serial(), device.id(), generation,
			UUID.fromString(run.path("syncRunId").asString()), run.path("nonce").asString(),
			run.path("settingsVersion").asLong(), run.path("payloadHash").asString()).bytes();
		perform(post(runs + "/" + run.path("syncRunId").asString() + "/ack")
			.header("Idempotency-Key", UUID.randomUUID()), tokens,
			toJson(Map.of("settingsVersion", run.path("settingsVersion").asLong(),
				"payloadHash", run.path("payloadHash").asString(), "proof", proof(device, transcript))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACKED"));
	}

	private ResultActions withdraw(JsonNode tokens, JsonNode prepared) throws Exception {
		String withdrawalId = prepared.path("operation").path("withdrawalId").asString();
		return perform(post(me(tokens, "/withdrawals/" + withdrawalId + "/execute"))
			.header("X-Withdrawal-Receipt", prepared.path("receiptToken").asString()), tokens,
			"{\"confirmed\":true}");
	}

	// ---------- 값 ----------

	private String email(long userId) {
		return jdbc.queryForObject("select email from users where id=?", String.class, userId);
	}

	private Map<String, String> proof(Device device, byte[] transcript) throws Exception {
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(device.keys().getPrivate());
		signer.update(transcript);
		return Map.of("protocolVersion", PROOF_PROTOCOL, "keyId", device.keyId(), "signature",
			encode(signer.sign()));
	}

	private static String encode(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private record Device(long id, String serial, String keyId, KeyPair keys) {
	}
}
