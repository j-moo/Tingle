package com.e106.tingle.user;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class ContactEmailIntegrationTest extends IntegrationTestSupport {

	@Test
	@DisplayName("본인은 저장된 연락 이메일을 받는다")
	void ownerReadsStoredEmail() throws Exception {
		JsonNode tokens = login("naver", newSocialId());
		agree(tokens).andExpect(status().isOk());
		jdbc.update("update users set email = ? where id = ?", "me@example.com", userIdOf(tokens));
		perform(get(me(tokens, "/contact-email")), tokens, null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.email").value("me@example.com"));
	}

	@Test
	@DisplayName("이메일이 없으면 email 은 null")
	void missingEmailIsNull() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		perform(get(me(tokens, "/contact-email")), tokens, null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.email").value(nullValue()));
	}

	@Test
	@DisplayName("다른 회원의 연락 이메일은 403 FORBIDDEN")
	void otherMembersCannotReadEmail() throws Exception {
		JsonNode owner = login("kakao", newSocialId());
		JsonNode other = login("kakao", newSocialId());
		agree(other).andExpect(status().isOk());
		perform(get(me(owner, "/contact-email")), other, null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("로그인하지 않으면 401")
	void anonymousCannotReadEmail() throws Exception {
		mockMvc.perform(get("/api/users/1/contact-email")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("약관 동의 전에는 403 ONBOARDING_REQUIRED")
	void blockedBeforeAgreement() throws Exception {
		JsonNode tokens = login("naver", newSocialId());
		perform(get(me(tokens, "/contact-email")), tokens, null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
	}
}
