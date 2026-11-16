package com.e106.tingle.user;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class ContactEmailIntegrationTest extends IntegrationTestSupport {

	@Test
	void ownerReadsStoredEmail() throws Exception {
		JsonNode tokens = login("naver", newSocialId());
		agree(tokens).andExpect(status().isOk());
		jdbc.update("update users set email = ? where id = ?", "me@example.com", userIdOf(tokens));
		perform(get(me(tokens, "/contact-email")), tokens, null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.email").value("me@example.com"));
	}

	@Test
	void missingEmailIsNull() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		perform(get(me(tokens, "/contact-email")), tokens, null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.email").value(nullValue()));
	}

	@Test
	void otherMembersCannotReadEmail() throws Exception {
		JsonNode owner = login("kakao", newSocialId());
		JsonNode other = login("kakao", newSocialId());
		agree(other).andExpect(status().isOk());
		perform(get(me(owner, "/contact-email")), other, null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void anonymousCannotReadEmail() throws Exception {
		mockMvc.perform(get("/api/users/1/contact-email")).andExpect(status().isUnauthorized());
	}
}
