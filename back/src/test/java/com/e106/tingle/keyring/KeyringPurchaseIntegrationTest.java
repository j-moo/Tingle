package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

@SpringBootTest(properties = "app.keyring.purchase-url=https://example.test/keyring")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class KeyringPurchaseIntegrationTest extends IntegrationTestSupport {

	private static final String PATH = "/api/v1/keyrings/purchase-info";

	@Test
	@DisplayName("약관 동의 후 키링 등록 전에도 구매 안내와 등록 필요 여부를 조회한다")
	void providesPurchaseInfoBeforeKeyringRegistration() throws Exception {
		JsonNode user = login("kakao", newSocialId());
		agree(user).andExpect(status().isOk());
		String response = perform(get(PATH), user, null).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.purchaseUrl").value("https://example.test/keyring"))
			.andExpect(jsonPath("$.registrationRequired").value(true))
			.andReturn().getResponse().getContentAsString();
		assertThat(jsonMapper.readTree(response).size()).isEqualTo(2);
	}

	@Test
	@DisplayName("본인의 활성 연결만 등록 완료로 보고 해제되면 다시 등록이 필요하다")
	void usesOnlyCurrentOwnedBindings() throws Exception {
		JsonNode user = login("kakao", newSocialId());
		agree(user).andExpect(status().isOk());
		long bindingId = bindKeyring(userIdOf(user));
		perform(get(PATH), user, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.registrationRequired").value(false));
		jdbc.update("update keyring_bindings set unbound_at=now() where binding_id=?", bindingId);
		perform(get(PATH), user, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.registrationRequired").value(true));
	}

	@Test
	@DisplayName("다른 회원의 기기와 실제 기기가 없는 연결은 등록 필요 여부를 바꾸지 않는다")
	void ignoresOtherOwnersAndDanglingBindings() throws Exception {
		JsonNode owner = login("kakao", newSocialId());
		bindKeyring(userIdOf(owner));
		JsonNode user = login("kakao", newSocialId());
		agree(user).andExpect(status().isOk());
		jdbc.update("insert into keyring_bindings(device_id,user_id,bound_at) values (?,?,now())",
			Long.MAX_VALUE, userIdOf(user));
		perform(get(PATH), user, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.registrationRequired").value(true));
	}

	@Test
	@DisplayName("인증 없음·잘못된 토큰·필수 약관 미동의는 조회할 수 없다")
	void rejectsMissingAuthenticationAndAgreement() throws Exception {
		mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
		mockMvc.perform(get(PATH).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
		JsonNode user = login("kakao", newSocialId());
		perform(get(PATH), user, null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
	}

	@Test
	@DisplayName("로그아웃하거나 정지된 계정은 구매 안내도 조회할 수 없다")
	void rejectsRevokedSessionAndSuspendedAccount() throws Exception {
		JsonNode user = login("kakao", newSocialId());
		agree(user).andExpect(status().isOk());
		logout(user.path("refreshToken").asString()).andExpect(status().isNoContent());
		perform(get(PATH), user, null).andExpect(status().isUnauthorized());
		JsonNode suspended = login("kakao", newSocialId());
		agree(suspended).andExpect(status().isOk());
		jdbc.update("update app_users set status='SUSPENDED' where user_id=?", userIdOf(suspended));
		perform(get(PATH), suspended, null).andExpect(status().isUnauthorized());
	}
}
