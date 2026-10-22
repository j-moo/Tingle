package com.e106.tingle.global;

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
import com.e106.tingle.TestProtectedController;
import com.e106.tingle.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import({TestcontainersConfiguration.class, TestProtectedController.class})
class HealthIntegrationTest extends IntegrationTestSupport {

	@Test
	@DisplayName("상태 확인은 토큰 없이 200 UP 을 주되 세부 정보(DB 종류 등)는 숨기고, 다른 actuator 경로는 열지 않는다")
	void healthIsPublicAndMinimal() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.components").doesNotExist());
		mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
	}
}
