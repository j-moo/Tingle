package com.e106.tingle.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.user.ProfilePhotoService;

import tools.jackson.databind.JsonNode;

@SpringBootTest(properties = {"app.withdrawal.enabled=true",
	"app.withdrawal.identity-secret=identity-test-secret-is-at-least-thirty-two-bytes-long",
	"app.withdrawal.receipt-secret=receipt-test-secret-is-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class MemberWithdrawalIntegrationTest extends IntegrationTestSupport {
	@Autowired
	private WithdrawalPhotoCleanup photoCleanup;
	@MockitoSpyBean
	private ProfilePhotoService photos;

	@Test
	void completesAtomicallyAndRecoversWithoutRevokedSession() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		long member = userIdOf(tokens);
		JsonNode prepared = prepare(tokens, UUID.randomUUID());
		execute(tokens, prepared).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.completedAt").exists()).andExpect(jsonPath("$.rejoinAvailableAt").exists());
		assertThat(jdbc.queryForObject("select count(*) from users where id=?", Long.class, member)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id=?", Long.class, member))
			.isZero();
		perform(get(me(tokens, "/withdrawal-eligibility")), tokens, null).andExpect(status().isUnauthorized());
		result(prepared, "forged.invalid.token").andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.status").value("COMPLETED"));
		result(prepared, null).andExpect(status().isOk());
		assertThat(jdbc.queryForMap("select user_id,session_id,request_key from withdrawal_operation where id=?",
			operationId(prepared)).values()).containsOnlyNulls();
	}

	@Test
	void prepareRetriesReturnSameReceiptAndFreshLoginCanRecoverPendingRequest() throws Exception {
		String subject = newSocialId();
		JsonNode first = login("kakao", subject);
		UUID key = UUID.randomUUID();
		JsonNode prepared = prepare(first, key);
		assertThat(prepare(first, key)).isEqualTo(prepared);
		JsonNode second = login("kakao", subject);
		JsonNode rebound = prepare(second, UUID.randomUUID());
		assertThat(operationId(rebound)).isEqualTo(operationId(prepared));
		assertThat(rebound.path("receiptToken").asString()).isEqualTo(prepared.path("receiptToken").asString());
		execute(second, rebound).andExpect(status().isOk());
	}

	@Test
	void ownedDeviceRollsBackExecutionAndDoesNotCreateCooldown() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		JsonNode prepared = prepare(tokens, UUID.randomUUID());
		jdbc.update("insert into keyring(serial_number,firmware_version,owner_user_id,state) "
			+ "values(?,?,?,'REGISTERED')", UUID.randomUUID().toString(), "test", userIdOf(tokens));
		execute(tokens, prepared).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WITHDRAWAL_KEYRING_REGISTERED"));
		result(prepared, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PREPARED"));
		assertThat(jdbc.queryForObject("select count(*) from users where id=?", Long.class, userIdOf(tokens)))
			.isEqualTo(1);
	}

	@Test
	void resultRequiresStrongReceiptAndExecutionRequiresConfirmation() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		JsonNode prepared = prepare(tokens, UUID.randomUUID());
		mockMvc.perform(post(resultPath(prepared)).header("X-Withdrawal-Receipt", "wrong"))
			.andExpect(status().isNotFound());
		perform(post(executePath(tokens, prepared)).header("X-Withdrawal-Receipt",
			prepared.path("receiptToken").asString()), tokens, "{\"confirmed\":false}")
			.andExpect(status().isBadRequest());
		JsonNode other = login("kakao", newSocialId());
		perform(post(executePath(tokens, prepared)).header("X-Withdrawal-Receipt",
			prepared.path("receiptToken").asString()), other, "{\"confirmed\":true}")
			.andExpect(status().isForbidden());
	}

	@Test
	void expiredPreparationCannotDeleteAccount() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		JsonNode prepared = prepare(tokens, UUID.randomUUID());
		timeShift.set(Duration.ofMinutes(31));
		result(prepared, null).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("WITHDRAWAL_OPERATION_EXPIRED"));
		assertThat(jdbc.queryForObject("select count(*) from users where id=?", Long.class, userIdOf(tokens)))
			.isEqualTo(1);
	}

	@Test
	void photoFailureRemainsDurableAndRecoveryShowsPendingUntilRetry() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		String key = "profile/" + userIdOf(tokens) + "/" + UUID.randomUUID() + ".jpg";
		jdbc.update("insert into profile(user_id,photo_url) values(?,?)", userIdOf(tokens), key);
		JsonNode prepared = prepare(tokens, UUID.randomUUID());
		execute(tokens, prepared).andExpect(status().isOk())
			.andExpect(jsonPath("$.externalCleanupStatus").value("PENDING"));
		doThrow(new IOException("synthetic storage failure")).doNothing().when(photos).deleteWithdrawnFile(anyString());
		assertThat(photoCleanup.runOne()).isTrue();
		result(prepared, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.externalCleanupStatus").value("PENDING"));
		timeShift.set(Duration.ofMinutes(2));
		assertThat(photoCleanup.runOne()).isTrue();
		result(prepared, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.externalCleanupStatus").value("COMPLETED"));
	}

	private JsonNode prepare(JsonNode tokens, UUID key) throws Exception {
		String body = perform(post(me(tokens, "/withdrawals")).header("Idempotency-Key", key), tokens, null)
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return jsonMapper.readTree(body);
	}

	private org.springframework.test.web.servlet.ResultActions execute(JsonNode tokens, JsonNode prepared)
		throws Exception {
		return perform(post(executePath(tokens, prepared)).header("X-Withdrawal-Receipt",
			prepared.path("receiptToken").asString()), tokens, "{\"confirmed\":true}");
	}

	private org.springframework.test.web.servlet.ResultActions result(JsonNode prepared, String staleToken)
		throws Exception {
		var request = post(resultPath(prepared)).header("X-Withdrawal-Receipt",
			prepared.path("receiptToken").asString()).contentType(MediaType.APPLICATION_JSON);
		if (staleToken != null) {
			request.header("Authorization", "Bearer " + staleToken);
		}
		return mockMvc.perform(request);
	}

	private String executePath(JsonNode tokens, JsonNode prepared) throws Exception {
		return me(tokens, "/withdrawals/" + operationId(prepared) + "/execute");
	}

	private static String resultPath(JsonNode prepared) {
		return "/api/withdrawals/" + operationId(prepared) + "/result";
	}

	private static UUID operationId(JsonNode prepared) {
		return UUID.fromString(prepared.path("operation").path("withdrawalId").asString());
	}
}
