package com.e106.tingle.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import tools.jackson.databind.JsonNode;

@SpringBootTest(properties = "app.push.token-encryption-key=" + PushInstallationIntegrationTest.TEST_KEY)
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class PushInstallationIntegrationTest extends IntegrationTestSupport {

	static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
	private static final String PATH = "/api/push-installations";
	private final PushTokenCipher cipher = new PushTokenCipher(TEST_KEY);

	@MockitoSpyBean
	private PushTokenEncryption encryption;

	@Test
	@DisplayName("본인 설치를 등록하고 서버 시각만 응답하며 토큰은 암호화해 저장한다")
	void registersOwnedInstallationWithoutExposingToken() throws Exception {
		JsonNode user = readyUser();
		UUID installationId = UUID.randomUUID();
		String token = "synthetic-first-token";

		String response = register(user, installationId, "ANDROID", token).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.serverTime").isNotEmpty())
			.andReturn().getResponse().getContentAsString();

		assertThat(response).doesNotContain(token, "installationId", "userId");
		assertThat(row(installationId)).containsEntry("user_id", userIdOf(user)).containsEntry("platform", "ANDROID");
		assertThat(storedToken(installationId)).startsWith("v1.").doesNotContain(token);
		assertThat(cipher.decrypt(installationId, userIdOf(user), storedToken(installationId))).isEqualTo(token);
	}

	@Test
	@DisplayName("동일 설치 재등록과 토큰 교체는 기존 행을 갱신하고 다른 설치를 변경하지 않는다")
	void replacesAndRepeatsWithoutDuplicatingRows() throws Exception {
		JsonNode user = readyUser();
		UUID installationId = UUID.randomUUID();
		UUID otherId = UUID.randomUUID();
		register(user, installationId, "ANDROID", "synthetic-old").andExpect(status().isOk());
		register(user, otherId, "ANDROID", "synthetic-other").andExpect(status().isOk());
		Object internalId = row(installationId).get("id");
		Object updatedAt = row(installationId).get("updated_at");
		String otherCiphertext = storedToken(otherId);
		timeShift.set(Duration.ofSeconds(1));

		register(user, installationId, "IOS", "synthetic-new").andExpect(status().isOk());
		register(user, installationId, "IOS", "synthetic-new").andExpect(status().isOk());

		assertThat(count(installationId)).isEqualTo(1);
		assertThat(row(installationId)).containsEntry("id", internalId).containsEntry("platform", "IOS");
		assertThat(row(installationId).get("updated_at")).isNotEqualTo(updatedAt);
		assertThat(cipher.decrypt(installationId, userIdOf(user), storedToken(installationId)))
			.isEqualTo("synthetic-new");
		assertThat(storedToken(otherId)).isEqualTo(otherCiphertext);
	}

	@Test
	@DisplayName("본인 설치만 실제 삭제하며 반복 해제는 404, 재등록은 새 행으로 처리한다")
	void removesAndReregistersOwnedInstallation() throws Exception {
		JsonNode user = readyUser();
		UUID installationId = UUID.randomUUID();
		register(user, installationId, "ANDROID", "synthetic-old").andExpect(status().isOk());
		Object oldId = row(installationId).get("id");

		perform(delete(PATH + "/" + installationId), user, null).andExpect(status().isNoContent())
			.andExpect(header().string("Cache-Control", "no-store")).andExpect(content().string(""));
		assertThat(count(installationId)).isZero();
		perform(delete(PATH + "/" + installationId), user, null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		register(user, installationId, "ANDROID", "synthetic-reregister").andExpect(status().isOk());

		assertThat(row(installationId).get("id")).isNotEqualTo(oldId);
		assertThat(cipher.decrypt(installationId, userIdOf(user), storedToken(installationId)))
			.isEqualTo("synthetic-reregister");
	}

	@Test
	@DisplayName("다른 회원이 UUID를 알아도 토큰 교체와 해제를 할 수 없다")
	void rejectsOtherOwnerWithoutChangingStoredRow() throws Exception {
		JsonNode owner = readyUser();
		JsonNode other = readyUser();
		UUID installationId = UUID.randomUUID();
		register(owner, installationId, "ANDROID", "synthetic-owner").andExpect(status().isOk());
		Map<String, Object> before = row(installationId);

		register(other, installationId, "IOS", "synthetic-attacker").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		String denied = perform(delete(PATH + "/" + installationId), other, null).andExpect(status().isNotFound())
			.andReturn().getResponse().getContentAsString();
		String absent = perform(delete(PATH + "/" + UUID.randomUUID()), other, null)
			.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

		assertThat(denied).isEqualTo(absent).doesNotContain("synthetic-owner", "synthetic-attacker");
		assertThat(row(installationId)).isEqualTo(before);
	}

	@Test
	@DisplayName("인증 없음·잘못된 토큰·가입 미완료 요청은 저장 전에 차단한다")
	void rejectsUnauthenticatedAndIncompleteUsers() throws Exception {
		UUID installationId = UUID.randomUUID();
		String body = registrationBody(installationId, "ANDROID", "synthetic-token");
		mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete(PATH + "/" + installationId)).andExpect(status().isUnauthorized());
		mockMvc.perform(put(PATH).header("Authorization", "Bearer invalid")
			.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
		JsonNode incomplete = login("kakao", newSocialId());
		perform(put(PATH), incomplete, body).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
		assertThat(count(installationId)).isZero();
	}

	@Test
	@DisplayName("로그아웃된 세션과 정지 회원은 기존 설치를 갱신하거나 삭제할 수 없다")
	void rejectsRevokedSessionAndInactiveAccount() throws Exception {
		JsonNode user = readyUser();
		UUID installationId = UUID.randomUUID();
		register(user, installationId, "ANDROID", "synthetic-original").andExpect(status().isOk());
		logout(user.path("refreshToken").asString()).andExpect(status().isNoContent());
		Map<String, Object> before = row(installationId);
		assertThat(before).containsEntry("user_id", null).containsEntry("login_family_id", null)
			.containsEntry("binding_id", null).containsEntry("linked_at", null);
		register(user, installationId, "ANDROID", "synthetic-revoked").andExpect(status().isUnauthorized());
		perform(delete(PATH + "/" + installationId), user, null).andExpect(status().isUnauthorized());
		assertThat(row(installationId)).isEqualTo(before);

		JsonNode suspended = readyUser();
		jdbc.update("update users set status='SUSPENDED' where id=?", userIdOf(suspended));
		register(suspended, UUID.randomUUID(), "ANDROID", "synthetic-suspended")
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("잘못된 UUID·플랫폼·빈 토큰·알 수 없는 필드는 400이고 토큰을 응답하지 않는다")
	void validatesInputWithoutLeakingRejectedToken() throws Exception {
		JsonNode user = readyUser();
		UUID installationId = UUID.randomUUID();
		List<Map<String, Object>> invalid = List.of(
			Map.of("platform", "ANDROID", "token", "synthetic-secret"),
			Map.of("installationId", "invalid", "platform", "ANDROID", "token", "synthetic-secret"),
			Map.of("installationId", installationId, "platform", "DESKTOP", "token", "synthetic-secret"),
			Map.of("installationId", installationId, "platform", "ANDROID", "token", " \t"),
			Map.of("installationId", installationId, "platform", "ANDROID", "token", "synthetic-secret", "userId", 99));
		for (Map<String, Object> body : invalid) {
			String response = perform(put(PATH), user, toJson(body)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andReturn().getResponse().getContentAsString();
			assertThat(response).doesNotContain("synthetic-secret");
		}
		perform(delete(PATH + "/invalid"), user, null).andExpect(status().isBadRequest());
		assertThat(count(installationId)).isZero();
	}

	@Test
	@DisplayName("같은 회원의 동시 등록은 중복 행 없이 한 설치로 직렬화한다")
	void serializesConcurrentRegistrationsForSameOwner() throws Exception {
		JsonNode user = readyUser();
		UUID installationId = UUID.randomUUID();
		List<Integer> statuses = runConcurrently(
			() -> register(user, installationId, "ANDROID", "synthetic-one").andReturn().getResponse().getStatus(),
			() -> register(user, installationId, "ANDROID", "synthetic-two").andReturn().getResponse().getStatus());

		assertThat(statuses).containsOnly(200);
		assertThat(count(installationId)).isEqualTo(1);
		assertThat(cipher.decrypt(installationId, userIdOf(user), storedToken(installationId)))
			.isIn("synthetic-one", "synthetic-two");
	}

	@Test
	@DisplayName("서로 다른 회원의 동시 최초 등록에서는 승자 한 명의 소유권만 유지한다")
	void preservesOwnerUnderConcurrentClaims() throws Exception {
		JsonNode first = readyUser();
		JsonNode second = readyUser();
		UUID installationId = UUID.randomUUID();
		List<Integer> statuses = runConcurrently(
			() -> register(first, installationId, "ANDROID", "synthetic-first").andReturn().getResponse().getStatus(),
			() -> register(second, installationId, "ANDROID", "synthetic-second")
				.andReturn().getResponse().getStatus());

		assertThat(statuses).containsExactlyInAnyOrder(200, 404);
		assertThat(count(installationId)).isEqualTo(1);
		long winningUser = ((Number)row(installationId).get("user_id")).longValue();
		String expected = winningUser == userIdOf(first)
			? "synthetic-first" : "synthetic-second";
		assertThat(cipher.decrypt(installationId, winningUser, storedToken(installationId))).isEqualTo(expected);
	}

	@Test
	@DisplayName("암호화 키를 사용할 수 없으면 등록을 거부하고 기존 저장값을 보존하며 해제는 가능하다")
	void unavailableEncryptionDoesNotPreventRemoval() throws Exception {
		JsonNode user = readyUser();
		UUID installationId = UUID.randomUUID();
		register(user, installationId, "ANDROID", "synthetic-original").andExpect(status().isOk());
		String before = storedToken(installationId);
		doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE))
			.when(encryption).encrypt(any(), anyLong(), anyString());

		register(user, installationId, "ANDROID", "synthetic-replacement").andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
		assertThat(storedToken(installationId)).isEqualTo(before);
		perform(delete(PATH + "/" + installationId), user, null).andExpect(status().isNoContent());
		assertThat(count(installationId)).isZero();
	}

	private JsonNode readyUser() throws Exception {
		JsonNode user = login("kakao", newSocialId());
		completeOnboarding(user);
		return user;
	}

	private ResultActions register(JsonNode user, UUID installationId, String platform, String token) throws Exception {
		return perform(put(PATH), user, registrationBody(installationId, platform, token));
	}

	private String registrationBody(UUID installationId, String platform, String token) throws Exception {
		return toJson(Map.of("installationId", installationId, "platform", platform, "token", token));
	}

	private Map<String, Object> row(UUID installationId) {
		return jdbc.queryForMap("select * from push_installation where client_installation_id=?", installationId);
	}

	private String storedToken(UUID installationId) {
		return (String)row(installationId).get("token_ciphertext");
	}

	private int count(UUID installationId) {
		return jdbc.queryForObject("select count(*) from push_installation where client_installation_id=?",
			Integer.class, installationId);
	}
}
