package com.e106.tingle.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.auth.dto.LogoutRequest;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.push.PushDeliveryLookup.Candidate;
import com.e106.tingle.push.dto.RegisterPushRequest;

import tools.jackson.databind.JsonNode;

@SpringBootTest(properties = "app.push.token-encryption-key=" + PushInstallationIntegrationTest.TEST_KEY)
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class PushSessionIntegrationTest extends IntegrationTestSupport {

	private static final String PATH = "/api/v1/push-installations";

	@Autowired
	private PushDeliveryLookup lookup;

	@Autowired
	private PushInstallationService installations;

	@Test
	@DisplayName("일반 로그아웃은 새 제안 연결을 유지하고 대기 중인 채팅 알림 대상은 제외한다")
	void normalLogoutPreservesOnlyNewProposalDelivery() throws Exception {
		JsonNode user = readyUser();
		long userId = userIdOf(user);
		UUID id = UUID.randomUUID();
		register(user, id, "synthetic-token").andExpect(status().isOk());
		List<Candidate> queued = Arrays.stream(PushNotificationKind.values())
			.map(kind -> lookup.candidates(userId, kind).getFirst()).toList();

		logout(user.path("refreshToken").asString()).andExpect(status().isNoContent());

		assertThat(count(id)).isEqualTo(1);
		for (Candidate candidate : queued) {
			assertThat(lookup.resolve(candidate).isPresent())
				.isEqualTo(candidate.kind() == PushNotificationKind.NEW_PROPOSAL);
		}
		assertThat(lookup.candidates(userId, PushNotificationKind.CHAT_MESSAGE)).isEmpty();
		PushDeliveryTarget target = lookup.resolve(queued.getFirst()).orElseThrow();
		assertThat(target.token()).isEqualTo("synthetic-token");
		assertThat(target.toString()).contains("[REDACTED]").doesNotContain(target.token());
		assertThat(toJson(queued)).doesNotContain("synthetic-token", "tokenCiphertext");
	}

	@Test
	@DisplayName("다른 설치의 활성 로그인이 로그아웃한 설치의 채팅 알림 권한을 되살리지 않는다")
	void isolatesSessionStatePerInstallation() throws Exception {
		String socialId = newSocialId();
		JsonNode first = login("kakao", socialId);
		completeOnboarding(first);
		JsonNode second = login("kakao", socialId);
		UUID firstId = UUID.randomUUID();
		UUID secondId = UUID.randomUUID();
		register(first, firstId, "synthetic-first").andExpect(status().isOk());
		register(second, secondId, "synthetic-second").andExpect(status().isOk());

		logout(first.path("refreshToken").asString()).andExpect(status().isNoContent());

		assertThat(lookup.candidates(userIdOf(first), PushNotificationKind.CHAT_END_NOTICE))
			.extracting(Candidate::installationId).containsExactly(secondId);
		assertThat(lookup.candidates(userIdOf(first), PushNotificationKind.NEW_PROPOSAL)).hasSize(2);
	}

	@Test
	@DisplayName("기존 증명으로 설치를 해제한 뒤 새 계정에 등록하며 지연 해제 재시도는 새 소유자를 건드리지 않는다")
	void switchesAccountWithoutTransferringByUuidAlone() throws Exception {
		JsonNode oldUser = readyUser();
		JsonNode newUser = readyUser();
		UUID id = UUID.randomUUID();
		register(oldUser, id, "synthetic-old").andExpect(status().isOk());
		Candidate queued = lookup.candidates(userIdOf(oldUser), PushNotificationKind.NEW_PROPOSAL).getFirst();
		register(newUser, id, "synthetic-new").andExpect(status().isNotFound());
		logout(oldUser.path("refreshToken").asString()).andExpect(status().isNoContent());

		release(oldUser, id).andExpect(status().isNoContent());
		register(newUser, id, "synthetic-new").andExpect(status().isOk());
		release(oldUser, id).andExpect(status().isNoContent());

		assertThat(lookup.resolve(queued)).isEmpty();
		assertThat(lookup.candidates(userIdOf(oldUser), PushNotificationKind.NEW_PROPOSAL)).isEmpty();
		Candidate current = lookup.candidates(userIdOf(newUser), PushNotificationKind.CHAT_ROOM_CREATED).getFirst();
		assertThat(lookup.resolve(current).orElseThrow().token()).isEqualTo("synthetic-new");
	}

	@Test
	@DisplayName("다른 회원이나 다른 세션의 리프레시 증명으로 설치 연결을 해제할 수 없다")
	void releaseRequiresMatchingOwnerAndFamily() throws Exception {
		String socialId = newSocialId();
		JsonNode owner = login("kakao", socialId);
		completeOnboarding(owner);
		JsonNode otherSession = login("kakao", socialId);
		JsonNode otherUser = readyUser();
		UUID id = UUID.randomUUID();
		register(owner, id, "synthetic-owner").andExpect(status().isOk());
		Candidate queued = lookup.candidates(userIdOf(owner), PushNotificationKind.CHAT_MESSAGE).getFirst();

		release(otherSession, id).andExpect(status().isNoContent());
		release(otherUser, id).andExpect(status().isNoContent());

		assertThat(count(id)).isEqualTo(1);
		assertThat(lookup.resolve(queued).orElseThrow().token()).isEqualTo("synthetic-owner");
	}

	@Test
	@DisplayName("확인할 수 없는 증명이나 잘못된 설치 형식은 연결을 바꾸지 않는다")
	void rejectsInvalidReleaseProofAndInput() throws Exception {
		JsonNode owner = readyUser();
		UUID id = UUID.randomUUID();
		register(owner, id, "synthetic-owner").andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
			.content(toJson(Map.of("refreshToken", "unknown", "releaseInstallationId", id))))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
			.content(toJson(Map.of("refreshToken", owner.path("refreshToken").asString(),
				"releaseInstallationId", "invalid")))).andExpect(status().isBadRequest());
		assertThat(lookup.candidates(userIdOf(owner), PushNotificationKind.CHAT_MESSAGE)).hasSize(1);
		assertThat(new LogoutRequest("synthetic-refresh-secret", id).toString())
			.doesNotContain("synthetic-refresh-secret");
	}

	@Test
	@DisplayName("JWT 검사 이후 폐기된 세션도 저장 트랜잭션 안의 재검사에서 차단한다")
	void rechecksRevocationWithinMutationTransaction() throws Exception {
		JsonNode user = readyUser();
		long userId = userIdOf(user);
		UUID family = familyIdOf(user);
		UUID id = UUID.randomUUID();
		logout(user.path("refreshToken").asString()).andExpect(status().isNoContent());

		assertThatThrownBy(() -> installations.register(userId, family,
			new RegisterPushRequest(id, PushPlatform.ANDROID, "synthetic-stale")))
			.isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SESSION_REVOKED));
		assertThat(count(id)).isZero();
	}

	@Test
	@DisplayName("등록과 로그아웃이 경합해도 종료 후 채팅 수신 권한을 되살리지 않는다")
	void registrationLogoutRaceDoesNotResurrectSession() throws Exception {
		JsonNode user = readyUser();
		UUID id = UUID.randomUUID();
		register(user, id, "synthetic-original").andExpect(status().isOk());

		List<Integer> results = runConcurrently(
			() -> register(user, id, "synthetic-race").andReturn().getResponse().getStatus(),
			() -> logout(user.path("refreshToken").asString()).andReturn().getResponse().getStatus());

		assertThat(results.getFirst()).isIn(200, 401);
		assertThat(results.getLast()).isEqualTo(204);
		assertThat(lookup.candidates(userIdOf(user), PushNotificationKind.CHAT_MESSAGE)).isEmpty();
		assertThat(lookup.candidates(userIdOf(user), PushNotificationKind.NEW_PROPOSAL)).hasSize(1);
	}

	@Test
	@DisplayName("토큰 교체·해제·같은 UUID 재등록 후 예전 전달 후보는 더 이상 사용할 수 없다")
	void invalidatesQueuedCandidatesAfterBindingChanges() throws Exception {
		JsonNode user = readyUser();
		long userId = userIdOf(user);
		UUID id = UUID.randomUUID();
		register(user, id, "synthetic-old").andExpect(status().isOk());
		Candidate beforeRotation = lookup.candidates(userId, PushNotificationKind.NEW_PROPOSAL).getFirst();
		register(user, id, "synthetic-new").andExpect(status().isOk());
		assertThat(lookup.resolve(beforeRotation)).isEmpty();
		Candidate beforeRemoval = lookup.candidates(userId, PushNotificationKind.NEW_PROPOSAL).getFirst();
		perform(delete(PATH + "/" + id), user, null).andExpect(status().isNoContent());
		register(user, id, "synthetic-new").andExpect(status().isOk());
		assertThat(lookup.resolve(beforeRemoval)).isEmpty();
		assertThat(lookup.resolve(lookup.candidates(userId, PushNotificationKind.NEW_PROPOSAL).getFirst()))
			.isPresent();
	}

	@Test
	@DisplayName("리프레시 회전은 같은 설치 세션을 유지하고 만료한 세션은 채팅 전달에서 제외한다")
	void refreshRotationAndExpiryRespectBoundFamily() throws Exception {
		JsonNode user = readyUser();
		UUID id = UUID.randomUUID();
		register(user, id, "synthetic-token").andExpect(status().isOk());
		Candidate queued = lookup.candidates(userIdOf(user), PushNotificationKind.CHAT_MESSAGE).getFirst();
		refresh(user.path("refreshToken").asString()).andExpect(status().isOk());
		assertThat(lookup.resolve(queued)).isPresent();
		jdbc.update("update refresh_tokens set expires_at=? where family_id=?",
			Timestamp.from(Instant.now().minusSeconds(1)), familyIdOf(user));
		assertThat(lookup.resolve(queued)).isEmpty();
		assertThat(lookup.candidates(userIdOf(user), PushNotificationKind.NEW_PROPOSAL)).hasSize(1);
	}

	@Test
	@DisplayName("재로그인해 같은 설치를 재등록하면 이전 세션의 지연 로그아웃은 새 연결을 지우지 않는다")
	void staleLogoutCannotDeleteNewLoginBinding() throws Exception {
		String socialId = newSocialId();
		JsonNode first = login("kakao", socialId);
		completeOnboarding(first);
		UUID id = UUID.randomUUID();
		register(first, id, "synthetic-old").andExpect(status().isOk());
		Candidate old = lookup.candidates(userIdOf(first), PushNotificationKind.CHAT_MESSAGE).getFirst();
		logout(first.path("refreshToken").asString()).andExpect(status().isNoContent());
		JsonNode second = login("kakao", socialId);
		register(second, id, "synthetic-current").andExpect(status().isOk());
		release(first, id).andExpect(status().isNoContent());
		assertThat(lookup.resolve(old)).isEmpty();
		Candidate current = lookup.candidates(userIdOf(second), PushNotificationKind.CHAT_MESSAGE).getFirst();
		assertThat(lookup.resolve(current).orElseThrow().token()).isEqualTo("synthetic-current");
	}

	@Test
	@DisplayName("세션 미연결 기존 행은 새 제안만 허용하고 정지·탈퇴 계정은 모든 전달에서 제외한다")
	void excludesLegacySessionAndInactiveUser() throws Exception {
		JsonNode user = readyUser();
		UUID id = UUID.randomUUID();
		long userId = userIdOf(user);
		register(user, id, "synthetic-token").andExpect(status().isOk());
		jdbc.update("update push_installations set login_family_id=null where client_installation_id=?", id);
		assertThat(lookup.candidates(userId, PushNotificationKind.CHAT_MESSAGE)).isEmpty();
		Candidate queued = lookup.candidates(userId, PushNotificationKind.NEW_PROPOSAL).getFirst();
		for (String state : List.of("SUSPENDED", "WITHDRAWN")) {
			jdbc.update("update app_users set status=? where user_id=?", state, userId);
			assertThat(lookup.resolve(queued)).isEmpty();
			assertThat(lookup.candidates(userId, PushNotificationKind.NEW_PROPOSAL)).isEmpty();
		}
	}

	@Test
	@DisplayName("변조된 암호문은 전달 토큰으로 내보내지 않고 복구 가능한 서비스 오류로 구분한다")
	void doesNotReturnCorruptedCiphertextAsToken() throws Exception {
		JsonNode user = readyUser();
		UUID id = UUID.randomUUID();
		register(user, id, "synthetic-secret").andExpect(status().isOk());
		jdbc.update("update push_installations set token_ciphertext='corrupt' where client_installation_id=?", id);
		Candidate candidate = lookup.candidates(userIdOf(user), PushNotificationKind.NEW_PROPOSAL).getFirst();
		assertThatThrownBy(() -> lookup.resolve(candidate)).isInstanceOfSatisfying(BusinessException.class,
			error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
			.hasNoCause().hasMessage("SERVICE_UNAVAILABLE");
	}

	private JsonNode readyUser() throws Exception {
		JsonNode user = login("kakao", newSocialId());
		completeOnboarding(user);
		return user;
	}

	private ResultActions register(JsonNode user, UUID id, String token) throws Exception {
		return perform(put(PATH), user, toJson(Map.of("installationId", id, "platform", "ANDROID", "token", token)));
	}

	private ResultActions release(JsonNode user, UUID id) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/logout")
			.contentType(MediaType.APPLICATION_JSON)
			.content(toJson(Map.of("refreshToken", user.path("refreshToken").asString(),
				"releaseInstallationId", id))));
	}

	private UUID familyIdOf(JsonNode user) throws Exception {
		String payload = user.path("accessToken").asString().split("\\.")[1];
		return UUID.fromString(jsonMapper.readTree(Base64.getUrlDecoder().decode(payload)).path("sid").asString());
	}

	private int count(UUID id) {
		return jdbc.queryForObject("select count(*) from push_installations where client_installation_id=?",
			Integer.class, id);
	}
}
