package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.auth.RefreshTokenRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.dto.Challenge;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.UserRepository;

import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class RegistrationChallengeIntegrationTest extends IntegrationTestSupport {

	private static final String PATH = "/api/keyrings/registration-challenges";

	@Autowired
	private RegistrationChallengeService service;

	@Autowired
	private RegistrationChallengeRepository challenges;

	@Autowired
	private UserRepository users;

	@Autowired
	private RefreshTokenRepository sessions;

	@Autowired
	private OnboardingService onboarding;

	@Autowired
	private PlatformTransactionManager transactions;

	@Test
	void issuesOwnedUuidNonceAndExactlyFiveMinuteExpiryWithoutChangingOnboarding() throws Exception {
		JsonNode user = eligibleUser();
		JsonNode other = eligibleUser();
		Instant now = fixedNow();
		UUID key = UUID.randomUUID();
		String body = perform(post(PATH).header("Idempotency-Key", key)
			.param("userId", Long.toString(userIdOf(other))), user, null)
			.andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(header().exists("X-Request-Id"))
			.andReturn().getResponse().getContentAsString();
		JsonNode response = jsonMapper.readTree(body);
		assertThat(response.size()).isEqualTo(4);
		assertThat(UUID.fromString(response.path("challengeId").asString()).version()).isEqualTo(4);
		assertThat(Base64.getUrlDecoder().decode(response.path("nonce").asString())).hasSize(32);
		assertThat(Instant.parse(response.path("expiresAt").asString())).isEqualTo(now.plusSeconds(300));
		assertThat(response.path("proofProtocolVersion").asString()).isEqualTo("tingle-device-proof-v2");
		String stored = jdbc.queryForObject("select nonce from registration_challenge "
			+ "where user_id=? and request_key=?", String.class, userIdOf(user), key);
		assertThat(stored).isEqualTo(response.path("nonce").asString());
		assertThat(body).doesNotContain("userId", "requestKey", "nonceHash", "consumedAt");
		assertThat(count(other)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from keyring where owner_user_id=?",
			Long.class, userIdOf(user))).isZero();
	}

	@Test
	void retriesKeepOriginalResponseAcrossExpiryAndConsumption() throws Exception {
		JsonNode user = eligibleUser();
		Instant now = fixedNow();
		UUID key = UUID.randomUUID();
		JsonNode original = issue(user, key);
		for (Instant retryAt : List.of(now.plusSeconds(299), now.plusSeconds(300), now.plusSeconds(301))) {
			given(clock.instant()).willReturn(retryAt);
			assertThat(issue(user, key)).isEqualTo(original);
		}
		long ownerId = userIdOf(user);
		// 발급 재시도와 실제 등록 소비의 유효성을 구분하는 합성 소비 결과다.
		new TransactionTemplate(transactions).executeWithoutResult(transaction ->
			challenges.findByUserIdAndRequestKey(ownerId, key).orElseThrow()
				.consume(123, UUID.randomUUID(), "synthetic-key", "a".repeat(64), now.plusSeconds(1)));
		assertThat(issue(user, key)).isEqualTo(original);
		assertThat(challenges.findByUserIdAndRequestKey(userIdOf(user), key).orElseThrow()
			.getConsumedAt()).isEqualTo(now.plusSeconds(1));
		assertThat(count(user)).isEqualTo(1);
	}

	@Test
	void newKeyCreatesIndependentChallengeWithoutExtendingOrConsumingOldOne() throws Exception {
		JsonNode user = eligibleUser();
		Instant now = fixedNow();
		UUID oldKey = UUID.randomUUID();
		JsonNode old = issue(user, oldKey);
		given(clock.instant()).willReturn(now.plusSeconds(30));
		JsonNode next = issue(user, UUID.randomUUID());
		assertThat(next.path("challengeId")).isNotEqualTo(old.path("challengeId"));
		assertThat(next.path("nonce")).isNotEqualTo(old.path("nonce"));
		assertThat(Instant.parse(next.path("expiresAt").asString())).isEqualTo(now.plusSeconds(330));
		assertThat(issue(user, oldKey)).isEqualTo(old);
		assertThat(challenges.findByUserIdAndRequestKey(userIdOf(user), oldKey).orElseThrow()
			.getConsumedAt()).isNull();
		assertThat(count(user)).isEqualTo(2);
	}

	@Test
	void sameRequestKeyIsScopedToAuthenticatedOwner() throws Exception {
		JsonNode first = eligibleUser();
		JsonNode other = eligibleUser();
		UUID key = UUID.randomUUID();
		JsonNode firstChallenge = issue(first, key);
		JsonNode otherChallenge = issue(other, key);
		assertThat(firstChallenge.path("challengeId")).isNotEqualTo(otherChallenge.path("challengeId"));
		assertThat(firstChallenge.path("nonce")).isNotEqualTo(otherChallenge.path("nonce"));
		assertThat(issue(first, key)).isEqualTo(firstChallenge);
		assertThat(count(first)).isEqualTo(1);
		assertThat(count(other)).isEqualTo(1);
	}

	@Test
	void concurrentSameKeyRequestsCreateOneChallengeAndReturnSameResponse() throws Exception {
		JsonNode user = eligibleUser();
		UUID key = UUID.randomUUID();
		List<JsonNode> results = runConcurrently(() -> issue(user, key), () -> issue(user, key),
			() -> issue(user, key));
		assertThat(results).allSatisfy(result -> assertThat(result).isEqualTo(results.getFirst()));
		assertThat(count(user)).isEqualTo(1);
	}

	@Test
	void concurrentDifferentKeysCreateSeparateChallenges() throws Exception {
		JsonNode user = eligibleUser();
		List<JsonNode> results = runConcurrently(() -> issue(user, UUID.randomUUID()),
			() -> issue(user, UUID.randomUUID()));
		assertThat(results.getFirst().path("challengeId")).isNotEqualTo(results.getLast().path("challengeId"));
		assertThat(results.getFirst().path("nonce")).isNotEqualTo(results.getLast().path("nonce"));
		assertThat(count(user)).isEqualTo(2);
	}

	@Test
	void enforcesAuthenticationAgreementsLogoutAndAccountStatus() throws Exception {
		mockMvc.perform(post(PATH).header("Idempotency-Key", UUID.randomUUID()))
			.andExpect(status().isUnauthorized()).andExpect(header().exists("X-Request-Id"))
			.andExpect(header().string("Cache-Control", "no-store"));
		mockMvc.perform(post(PATH).header("Authorization", "Bearer invalid"))
			.andExpect(status().isUnauthorized());
		JsonNode noAgreement = login("kakao", newSocialId());
		perform(post(PATH).header("Idempotency-Key", UUID.randomUUID()), noAgreement, null)
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
		JsonNode loggedOut = eligibleUser();
		logout(loggedOut.path("refreshToken").asString()).andExpect(status().isNoContent());
		perform(post(PATH).header("Idempotency-Key", UUID.randomUUID()), loggedOut, null)
			.andExpect(status().isUnauthorized());
		JsonNode suspended = eligibleUser();
		jdbc.update("update users set status='SUSPENDED' where id=?", userIdOf(suspended));
		perform(post(PATH).header("Idempotency-Key", UUID.randomUUID()), suspended, null)
			.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsMissingMalformedKeysAndUnexpectedBodyWithoutCreatingRows() throws Exception {
		JsonNode user = eligibleUser();
		perform(post(PATH), user, null).andExpect(status().isBadRequest())
			.andExpect(header().exists("X-Request-Id")).andExpect(header().string("Cache-Control", "no-store"));
		for (String key : List.of("invalid", "1-1-1-1-1")) {
			perform(post(PATH).header("Idempotency-Key", key), user, null)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
		perform(post(PATH).header("Idempotency-Key", UUID.randomUUID()), user, "{\"userId\":123}")
			.andExpect(status().isBadRequest());
		assertThat(count(user)).isZero();
	}

	@Test
	void rechecksRefreshSessionExpiryInsideTransaction() throws Exception {
		JsonNode user = eligibleUser();
		jdbc.update("update refresh_token set expires_at=? where family_id=?",
			Timestamp.from(clock.instant().minusSeconds(1)), familyOf(user));
		perform(post(PATH).header("Idempotency-Key", UUID.randomUUID()), user, null)
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
		assertThat(count(user)).isZero();
	}

	@Test
	void rejectsForeignSessionAndRechecksAgreementForServiceCaller() throws Exception {
		JsonNode owner = eligibleUser();
		JsonNode other = eligibleUser();
		assertBusinessError(() -> service.issue(userIdOf(owner), familyOf(other), UUID.randomUUID()),
			ErrorCode.SESSION_REVOKED);
		JsonNode noAgreement = login("kakao", newSocialId());
		assertBusinessError(() -> service.issue(userIdOf(noAgreement), familyOf(noAgreement), UUID.randomUUID()),
			ErrorCode.ONBOARDING_REQUIRED);
		assertThat(count(owner)).isZero();
	}

	@Test
	void storedResponseSurvivesAnotherServiceInstance() throws Exception {
		JsonNode user = eligibleUser();
		long owner = userIdOf(user);
		UUID family = familyOf(user);
		UUID key = UUID.randomUUID();
		JsonNode original = issue(user, key);
		RegistrationChallengeService another = new RegistrationChallengeService(challenges,
			users, sessions, onboarding, clock);
		Challenge replay = new TransactionTemplate(transactions).execute(transaction ->
			another.issue(owner, family, key));
		JsonNode replayJson = jsonMapper.valueToTree(replay);
		assertThat(replayJson).isEqualTo(original);
		assertThat(replay.toString()).doesNotContain(replay.nonce());
		assertThat(count(user)).isEqualTo(1);
	}

	@Test
	void rejectsOldVersionRouteWithoutCreatingChallenge() throws Exception {
		JsonNode user = eligibleUser();
		completeOnboarding(user);
		perform(post("/api/v1/keyrings/registration-challenges")
			.header("Idempotency-Key", UUID.randomUUID()), user, null).andExpect(status().isNotFound());
		assertThat(count(user)).isZero();
	}

	@Test
	void rejectsRevokedSessionEvenForExistingRequestKey() throws Exception {
		JsonNode user = eligibleUser();
		long owner = userIdOf(user);
		UUID family = familyOf(user);
		UUID key = UUID.randomUUID();
		issue(user, key);
		logout(user.path("refreshToken").asString()).andExpect(status().isNoContent());
		assertBusinessError(() -> service.issue(owner, family, key), ErrorCode.SESSION_REVOKED);
		assertThat(count(user)).isEqualTo(1);
	}

	@Test
	void rolledBackIssueCanBeRetriedWithoutLeavingAnOrphanChallenge() throws Exception {
		JsonNode user = eligibleUser();
		long owner = userIdOf(user);
		UUID family = familyOf(user);
		UUID key = UUID.randomUUID();
		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			service.issue(owner, family, key);
			throw new IllegalStateException("synthetic rollback");
		})).isInstanceOf(IllegalStateException.class).hasMessage("synthetic rollback");
		assertThat(count(user)).isZero();
		issue(user, key);
		assertThat(count(user)).isEqualTo(1);
	}

	private JsonNode eligibleUser() throws Exception {
		JsonNode user = login("kakao", newSocialId());
		agree(user).andExpect(status().isOk());
		return user;
	}

	private Instant fixedNow() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		given(clock.instant()).willReturn(now);
		return now;
	}

	private JsonNode issue(JsonNode user, UUID key) throws Exception {
		String body = perform(post(PATH).header("Idempotency-Key", key), user, null)
			.andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(header().exists("X-Request-Id"))
			.andReturn().getResponse().getContentAsString();
		return jsonMapper.readTree(body);
	}

	private long count(JsonNode user) throws Exception {
		return jdbc.queryForObject("select count(*) from registration_challenge where user_id=?",
			Long.class, userIdOf(user));
	}

	private UUID familyOf(JsonNode user) throws Exception {
		String payload = user.path("accessToken").asString().split("\\.")[1];
		return UUID.fromString(jsonMapper.readTree(Base64.getUrlDecoder().decode(payload)).path("sid").asString());
	}

	private void assertBusinessError(ThrowingAction action, ErrorCode code) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
	}

	@FunctionalInterface
	private interface ThrowingAction {
		void run() throws Exception;
	}
}
