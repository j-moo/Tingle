package com.e106.tingle.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestProtectedController;
import com.e106.tingle.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;

/** 신규 가입은 개발·운영 모두 키링이 필수다. 끄는 설정은 없고 앱이 보낸 값으로도 바꿀 수 없다. */
@SpringBootTest(properties = "tingle.test.signup-keyring-policy-context=true")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import({TestcontainersConfiguration.class, TestProtectedController.class})
class SignupKeyringPolicyIntegrationTest extends IntegrationTestSupport {

	@Autowired
	private UserRepository userRepository;

	@Test
	void requiresKeyringAndClientFlagsCannotDisableIt() throws Exception {
		JsonNode first = login("kakao", newSocialId());
		JsonNode second = login("kakao", newSocialId());
		agree(first).andExpect(status().isOk());
		agree(second).andExpect(status().isOk());
		onboarding(first, "KEYRING_REQUIRED");
		onboarding(second, "KEYRING_REQUIRED");

		perform(get(me(first, "/onboarding")).param("keyringRequirement", "OPTIONAL")
			.param("skipKeyring", "true").header("TINGLE_SIGNUP_KEYRING_REQUIRED", "false"), first, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.keyringRequirement").value("REQUIRED"))
			.andExpect(jsonPath("$.nextStep").value("KEYRING_REQUIRED"));
		assertThat(completedAt(userIdOf(first))).isNull();
	}

	private ResultActions onboarding(JsonNode tokens, String nextStep) throws Exception {
		return perform(get(me(tokens, "/onboarding")), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.keyringRequirement").value("REQUIRED"))
			.andExpect(jsonPath("$.nextStep").value(nextStep))
			.andExpect(jsonPath("$.signupCompleted").value(false));
	}

	private Instant completedAt(long userId) {
		return userRepository.findById(userId).orElseThrow().getSignupCompletedAt();
	}
}
