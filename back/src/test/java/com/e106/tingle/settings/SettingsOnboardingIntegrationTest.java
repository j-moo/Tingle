package com.e106.tingle.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;

/** 전체 앱·실제 JWT·PostgreSQL 검사. 소셜·분석·기기 ACK는 명시적인 기존 fixture다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SettingsOnboardingIntegrationTest extends IntegrationTestSupport {

	private static final String OPTIONS = "{\"matchingEnabled\":true,\"requirePartnerPhoto\":true}";
	private static final String ANY = "{\"desiredAgeMode\":\"ANY\",\"desiredAgeMin\":null,\"desiredAgeMax\":null}";

	@Test
	void settingsChangeInvalidatesReadinessWithoutChangingAckAgeOrExistingProposal() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		completeOnboarding(tokens);
		long userId = userIdOf(tokens);
		JsonNode other = login("kakao", newSocialId());
		long otherId = userIdOf(other);
		long matchingId = jdbc.queryForObject("insert into matching "
			+ "(user_low_id,user_high_id,status,proposed_at) values (?,?,'PENDING',now()) returning id",
			Long.class, Math.min(userId, otherId), Math.max(userId, otherId));
		jdbc.update("insert into matching_participant "
			+ "(matching_id,user_id,response,responded_at,decision_request_key) values (?,?,'ACCEPTED',now(),?)",
			matchingId, userId, UUID.randomUUID());
		Map<String, Object> proposalBefore = jdbc.queryForMap("select * from matching where id=?", matchingId);
		Map<String, Object> responseBefore = jdbc.queryForMap(
			"select * from matching_participant where matching_id=? and user_id=?", matchingId, userId);
		Map<String, Object> ageBefore = jdbc.queryForMap("select age_preference_mode,min_age,max_age "
			+ "from matching_preference where user_id=?", userId);
		Map<String, Object> deviceBefore = jdbc.queryForMap("select synced_settings_version,synced_at "
			+ "from keyring where owner_user_id=?", userId);
		perform(get(me(tokens, "/settings")), tokens, null).andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"1\""))
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.matchingEnabled").value(true))
			.andExpect(jsonPath("$.requirePartnerPhoto").value(false));
		perform(put(me(tokens, "/settings")).header("If-Match", "\"1\""), tokens, OPTIONS)
			.andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
			.andExpect(jsonPath("$.settingsVersion").value(2));
		perform(get(me(tokens, "/onboarding")), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.nextStep").value("SYNC_PENDING"))
			.andExpect(jsonPath("$.matchingReady").value(false))
			.andExpect(jsonPath("$.requiredSettingsVersion").value(2))
			.andExpect(jsonPath("$.acknowledgedSettingsVersion").value(1))
			.andExpect(jsonPath("$.signupCompleted").value(true));
		assertThat(jdbc.queryForMap("select * from matching where id=?", matchingId)).isEqualTo(proposalBefore);
		assertThat(jdbc.queryForMap("select * from matching_participant where matching_id=? and user_id=?",
			matchingId, userId)).isEqualTo(responseBefore);
		assertThat(jdbc.queryForMap("select age_preference_mode,min_age,max_age from matching_preference "
			+ "where user_id=?", userId)).isEqualTo(ageBefore);
		assertThat(jdbc.queryForMap("select synced_settings_version,synced_at from keyring where owner_user_id=?",
			userId)).isEqualTo(deviceBefore);
		perform(put(me(tokens, "/preferences")).header("If-Match", "\"1\""), tokens, ANY)
			.andExpect(status().isPreconditionFailed());
		perform(put(me(tokens, "/preferences")).header("If-Match", "\"2\""), tokens, ANY)
			.andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""));
		perform(get(me(tokens, "/settings")), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.requirePartnerPhoto").value(true))
			.andExpect(jsonPath("$.settingsVersion").value(3));
	}

	@Test
	void realAuthenticationOwnerOnboardingAndConditionalWritesKeepTheirStatusCodes() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		long userId = userIdOf(tokens);
		mockMvc.perform(get("/api/users/{userId}/settings", userId)).andExpect(status().isUnauthorized());
		perform(get("/api/users/{userId}/settings", userId + 1), tokens, null)
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		perform(get(me(tokens, "/settings")), tokens, null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
		completeOnboarding(tokens);
		perform(put(me(tokens, "/settings")), tokens, OPTIONS).andExpect(status().isPreconditionRequired())
			.andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
		perform(put(me(tokens, "/settings")).header("If-Match", "\"0\""), tokens, OPTIONS)
			.andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
		assertThat(jdbc.queryForObject("select settings_version from user_setting where user_id=?",
			Long.class, userId)).isEqualTo(1);
		logout(tokens.path("refreshToken").asString()).andExpect(status().isNoContent());
		perform(get(me(tokens, "/settings")), tokens, null).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
	}
}
