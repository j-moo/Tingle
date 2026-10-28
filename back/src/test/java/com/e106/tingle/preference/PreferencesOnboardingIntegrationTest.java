package com.e106.tingle.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;

/** 실제 전체 앱·JWT·JPA 22표에서 인증/가입/희망 조건을 연결한다. 기기/분석은 명시적인 fixture다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PreferencesOnboardingIntegrationTest extends IntegrationTestSupport {

	private static final String ANY = "{\"desiredAgeMode\":\"ANY\",\"desiredAgeMin\":null,\"desiredAgeMax\":null}";

	@Test
	void actualLoginPreferencesAndAckUseTheSameSettingsAndOnboardingState() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		long userId = userIdOf(tokens);
		assertThat(jdbc.queryForObject("select settings_version from user_setting where user_id=?",
			Long.class, userId)).isZero();
		agree(tokens).andExpect(status().isOk());
		long keyringId = bindKeyring(userId);
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
		completeProfile(tokens).andExpect(status().isOk());
		long personalityId = finishSurvey(userId);
		onboarding(tokens, "PREFERENCES_REQUIRED", 0, false);
		perform(get(me(tokens, "/preferences")), tokens, null).andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"0\""))
			.andExpect(jsonPath("$.desiredAgeMode").isEmpty())
			.andExpect(jsonPath("$.desiredGenders[0]").value("MALE"));
		perform(put(me(tokens, "/preferences")).header("If-Match", "\"0\""), tokens, ANY)
			.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
		onboarding(tokens, "SYNC_PENDING", 1, false);
		acknowledgeSync(userId, keyringId, personalityId);
		onboarding(tokens, "READY", 1, true);
		perform(put(me(tokens, "/preferences")).header("If-Match", "\"1\""), tokens,
			"{\"desiredAgeMode\":\"RANGE\",\"desiredAgeMin\":25,\"desiredAgeMax\":null}")
			.andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
		onboarding(tokens, "SYNC_PENDING", 2, false);
		perform(get(me(tokens, "/onboarding")), tokens, null).andExpect(jsonPath("$.signupCompleted").value(true))
			.andExpect(jsonPath("$.acknowledgedSettingsVersion").value(1));
		perform(put(me(tokens, "/preferences")).header("If-Match", "\"1\""), tokens, ANY)
			.andExpect(status().isPreconditionFailed());
		assertThat(jdbc.queryForObject("select settings_version from user_setting where user_id=?",
			Long.class, userId)).isEqualTo(2);
		acknowledgeSync(userId, keyringId, personalityId);
		onboarding(tokens, "READY", 2, true);
	}

	@Test
	void authenticatedOwnerChecksAndLogoutApplyToTheRealPreferencesRoute() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		long userId = userIdOf(tokens);
		perform(get("/api/users/{userId}/preferences", userId + 1), tokens, null)
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		perform(get(me(tokens, "/preferences")), tokens, null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
		logout(tokens.path("refreshToken").asString()).andExpect(status().isNoContent());
		perform(get(me(tokens, "/preferences")), tokens, null).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
		assertThat(jdbc.queryForObject("select settings_version from user_setting where user_id=?",
			Long.class, userId)).isZero();
	}

	private void onboarding(JsonNode tokens, String step, long version, boolean ready) throws Exception {
		perform(get(me(tokens, "/onboarding")), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.nextStep").value(step))
			.andExpect(jsonPath("$.requiredSettingsVersion").value(version))
			.andExpect(jsonPath("$.matchingReady").value(ready));
	}
}
