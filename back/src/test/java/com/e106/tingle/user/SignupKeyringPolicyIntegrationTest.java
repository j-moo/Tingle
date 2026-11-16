package com.e106.tingle.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestProtectedController;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.DeviceProof;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;

import tools.jackson.databind.JsonNode;

@SpringBootTest(properties = "tingle.test.signup-keyring-policy-context=true")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import({TestcontainersConfiguration.class, TestProtectedController.class})
class SignupKeyringPolicyIntegrationTest extends IntegrationTestSupport {

	private static final String REQUIRED = "TINGLE_SIGNUP_KEYRING_REQUIRED";
	private static final String ENCOUNTER_SYNC = "/api/offline-encounters/sync";

	@Autowired
	private ConfigurableEnvironment environment;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private OnboardingService onboardingService;

	private String[] activeProfiles;

	@BeforeEach
	void rememberProfiles() {
		activeProfiles = environment.getActiveProfiles();
	}

	@AfterEach
	void restoreDefaults() {
		environment.setActiveProfiles(activeProfiles);
		setRequired(true);
	}

	@Test
	void completesOnlyAfterOtherSignupStepsAndKeepsDeviceAccessSeparate() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		long userId = userIdOf(tokens);
		setRequired(false);

		onboarding(tokens, "OPTIONAL", "AGREEMENT_REQUIRED", false);
		agree(tokens).andExpect(status().isOk()).andExpect(jsonPath("$.onboardingStatus").value("PROFILE_REQUIRED"));
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
		onboarding(tokens, "OPTIONAL", "PROFILE_REQUIRED", false);
		completeProfile(tokens).andExpect(status().isOk());
		onboarding(tokens, "OPTIONAL", "SURVEY_REQUIRED", false);
		finishSurvey(userId);
		onboarding(tokens, "OPTIONAL", "PREFERENCES_REQUIRED", false);
		assertThat(completedAt(userId)).isNull();

		savePreferences(tokens);
		// 키링 없이 가입하면 희망 조건이 마지막 단계다. 온보딩 조회 전에도 완료 시각이 남아야 한다.
		assertThat(completedAt(userId)).isNotNull();
		onboarding(tokens, "OPTIONAL", "READY", true)
			.andExpect(jsonPath("$.onboardingCompleted").value(true))
			.andExpect(jsonPath("$.matchingReady").value(false))
			.andExpect(jsonPath("$.acknowledgedSettingsVersion").value(nullValue()));
		Instant completedAt = completedAt(userId);
		assertThat(completedAt).isNotNull();
		onboarding(tokens, "OPTIONAL", "READY", true);
		assertThat(completedAt(userId)).isEqualTo(completedAt);
		assertThat(jdbc.queryForObject("select count(*) from keyring where owner_user_id=?", Long.class, userId))
			.isZero();

		long foreignOwnerId = userIdOf(login("kakao", newSocialId()));
		long foreignDevice = bindKeyring(foreignOwnerId);
		assertDeviceOperationsDenied(tokens, foreignDevice);
		setRequired(true);
		assertCompletedAccessWithRequiredPolicy(tokens, completedAt);
		assertDeviceOperationsDenied(tokens, foreignDevice);
	}

	@Test
	void reEnablingRecomputesIncompleteSignupAndAllowsRetryWhenDisabledAgain() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		long userId = userIdOf(tokens);
		setRequired(false);
		agree(tokens).andExpect(status().isOk());
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
		completeProfile(tokens).andExpect(status().isOk());
		finishSurvey(userId);
		onboarding(tokens, "OPTIONAL", "PREFERENCES_REQUIRED", false);

		setRequired(true);
		onboarding(tokens, "REQUIRED", "KEYRING_REQUIRED", false);
		agree(tokens).andExpect(status().isOk()).andExpect(jsonPath("$.onboardingStatus").value("KEYRING_REQUIRED"));
		assertThat(completedAt(userId)).isNull();

		setRequired(false);
		onboarding(tokens, "OPTIONAL", "PREFERENCES_REQUIRED", false);
		savePreferences(tokens);
		onboarding(tokens, "OPTIONAL", "READY", true);
	}

	@Test
	void defaultRequiresKeyringAndClientFlagsCannotDisableIt() throws Exception {
		JsonNode first = login("kakao", newSocialId());
		JsonNode second = login("kakao", newSocialId());
		agree(first).andExpect(status().isOk());
		agree(second).andExpect(status().isOk());
		onboarding(first, "REQUIRED", "KEYRING_REQUIRED", false);
		onboarding(second, "REQUIRED", "KEYRING_REQUIRED", false);

		perform(get(me(first, "/onboarding")).param("keyringRequirement", "OPTIONAL")
			.param("skipKeyring", "true").header(REQUIRED, "false"), first, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.keyringRequirement").value("REQUIRED"))
			.andExpect(jsonPath("$.nextStep").value("KEYRING_REQUIRED"));
		assertThat(completedAt(userIdOf(first))).isNull();
	}

	@Test
	void disabledPolicyAppliesToAllAccountsEvenInProduction() throws Exception {
		JsonNode first = login("kakao", newSocialId());
		JsonNode second = login("kakao", newSocialId());
		agree(first).andExpect(status().isOk());
		agree(second).andExpect(status().isOk());

		setRequired(false);
		onboarding(first, "OPTIONAL", "PROFILE_REQUIRED", false);
		onboarding(second, "OPTIONAL", "PROFILE_REQUIRED", false);
		environment.addActiveProfile("prod");
		onboarding(first, "OPTIONAL", "PROFILE_REQUIRED", false);
		onboarding(second, "OPTIONAL", "PROFILE_REQUIRED", false);

		setRequired(true);
		onboarding(first, "REQUIRED", "KEYRING_REQUIRED", false);
		onboarding(second, "REQUIRED", "KEYRING_REQUIRED", false);
	}

	// app.signup.keyring-required 는 기동 시 한 번 읽는다. 한 시나리오 안에서 전환을 재현하려고 주입된 값을 바꾼다.
	private void setRequired(boolean required) {
		Object target = AopTestUtils.getTargetObject(onboardingService);
		ReflectionTestUtils.setField(target, "keyringRequired", required);
	}

	private ResultActions onboarding(JsonNode tokens, String requirement, String nextStep, boolean completed)
		throws Exception {
		return perform(get(me(tokens, "/onboarding")), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.keyringRequirement").value(requirement))
			.andExpect(jsonPath("$.nextStep").value(nextStep))
			.andExpect(jsonPath("$.signupCompleted").value(completed));
	}

	private Instant completedAt(long userId) {
		return userRepository.findById(userId).orElseThrow().getSignupCompletedAt();
	}

	private void savePreferences(JsonNode tokens) throws Exception {
		String preferences = me(tokens, "/preferences");
		String version = perform(get(preferences), tokens, null).andExpect(status().isOk())
			.andReturn().getResponse().getHeader("ETag");
		perform(put(preferences).header("If-Match", version), tokens,
			"{\"desiredAgeMode\":\"ANY\",\"desiredAgeMin\":null,\"desiredAgeMax\":null}")
			.andExpect(status().isOk());
	}

	private void assertCompletedAccessWithRequiredPolicy(JsonNode tokens, Instant completedAt) throws Exception {
		onboarding(tokens, "REQUIRED", "KEYRING_REQUIRED", true)
			.andExpect(jsonPath("$.matchingReady").value(false));
		perform(get("/api/v1/test/protected"), tokens, null).andExpect(status().isOk());
		assertThat(completedAt(userIdOf(tokens))).isEqualTo(completedAt);
	}

	private void assertDeviceOperationsDenied(JsonNode tokens, long foreignDevice) throws Exception {
		perform(post("/api/keyrings/{deviceId}/sync-runs", foreignDevice)
			.header("Idempotency-Key", UUID.randomUUID()), tokens, null)
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

		var submission = new Submission(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
			"policy-1", "rule-1", UUID.randomUUID(), "test-peer", new DeviceProof("test", "opaque"));
		perform(post(ENCOUNTER_SYNC), tokens,
			toJson(Map.of("deviceId", foreignDevice, "submissions", List.of(submission))))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		perform(post(ENCOUNTER_SYNC), tokens, toJson(Map.of("submissions", List.of(submission))))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}
}
