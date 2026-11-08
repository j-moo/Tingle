package com.e106.tingle.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

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
class WithdrawalEligibilityIntegrationTest extends IntegrationTestSupport {

	@Test
	void accountWithoutDevicesCanCheckBeforeOnboarding() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		perform(get(me(tokens, "/withdrawal-eligibility")), tokens, null)
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.eligible").value(true))
			.andExpect(jsonPath("$.registeredKeyringCount").value(0))
			.andExpect(jsonPath("$.pendingResetCount").value(0))
			.andExpect(jsonPath("$.blockingCodes").isEmpty())
			.andExpect(jsonPath("$.checkedAt").exists());
	}

	@Test
	void ownedDeviceBlocksEvenWithInconsistentUnboundState() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		jdbc.update("insert into keyring(serial_number,firmware_version,owner_user_id,state) "
			+ "values (?,?,?,'UNBOUND')", UUID.randomUUID().toString(), "test", userIdOf(tokens));
		perform(get(me(tokens, "/withdrawal-eligibility")), tokens, null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(false))
			.andExpect(jsonPath("$.registeredKeyringCount").value(1))
			.andExpect(jsonPath("$.blockingCodes[0]").value("WITHDRAWAL_KEYRING_REGISTERED"));
	}

	@Test
	void expiredUnfinishedResetBlocksEvenWhenResetFeatureIsDisabled() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		jdbc.execute(Files.readString(Path.of("src/main/resources/database/keyring-reset-249.sql"))
			.replace("create table keyring_reset_run", "create table if not exists keyring_reset_run"));
		UUID run = UUID.randomUUID();
		jdbc.update("insert into keyring_reset_run(run_id,keyring_id,owner_user_id,owner_generation,request_key,"
			+ "discard_unsent,nonce,command,created_at,expires_at,from_ownership_epoch,request_hash,challenge_hash) "
			+ "values (?,?,?,?,?,true,'nonce','command',now(),now()-interval '1 hour',0,'hash','hash')",
			run, 999999L, userIdOf(tokens), UUID.randomUUID(), UUID.randomUUID());
		perform(get(me(tokens, "/withdrawal-eligibility")), tokens, null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(false))
			.andExpect(jsonPath("$.pendingResetCount").value(1))
			.andExpect(jsonPath("$.blockingCodes[0]").value("WITHDRAWAL_RESET_PENDING"));
		jdbc.update("update keyring_reset_run set completed_at=?,proof_hash='verified' where run_id=?",
			java.sql.Timestamp.from(Instant.now()), run);
		perform(get(me(tokens, "/withdrawal-eligibility")), tokens, null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(true));
	}

	@Test
	void otherMembersCannotReadEligibility() throws Exception {
		JsonNode owner = login("kakao", newSocialId());
		JsonNode other = login("kakao", newSocialId());
		perform(get(me(owner, "/withdrawal-eligibility")), other, null).andExpect(status().isForbidden());
	}

	@Test
	void anonymousCannotReadEligibility() throws Exception {
		mockMvc.perform(get("/api/users/1/withdrawal-eligibility")).andExpect(status().isUnauthorized());
	}
}
