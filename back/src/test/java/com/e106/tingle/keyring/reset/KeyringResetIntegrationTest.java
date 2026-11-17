package com.e106.tingle.keyring.reset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.global.error.BusinessException;

import tools.jackson.databind.JsonNode;

/** Disposable PostgreSQL only. The device protocol is explicitly synthetic, never firmware proof. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class KeyringResetIntegrationTest extends IntegrationTestSupport {
	@MockitoBean
	private ResetDeviceProtocol protocol;
	@Autowired
	private KeyringResetService service;
	@Autowired
	private KeyringResetGuard guard;

	@BeforeEach
	void syntheticDeviceOnly() {
		given(protocol.issue(any())).willAnswer(call -> "synthetic-command:"
			+ ((ResetDeviceProtocol.Context)call.getArgument(0)).runId());
		given(protocol.verify(any(), anyString())).willAnswer(call -> {
			ResetDeviceProtocol.Context context = call.getArgument(0);
			return proof(context.runId(), context.ownerGeneration(), context.nonce(), context.discardUnsent())
				.equals(call.getArgument(1));
		});
	}

	@Test
	void persistsPendingAndRequiresDeviceProofBeforeReleasingOwnership() throws Exception {
		Fixture fixture = fixture();
		UUID key = UUID.randomUUID();
		JsonNode run = create(fixture, key, false, 201);
		String path = path(fixture) + "/" + run.path("resetRunId").asString();
		assertThat(create(fixture, key, false, 200)).isEqualTo(run);
		create(fixture, key, true, 409);
		create(fixture, UUID.randomUUID(), false, 409);
		assertThatThrownBy(() -> guard.requireNoPendingReset(fixture.device())).isInstanceOf(BusinessException.class);
		perform(post(path + "/ack"), fixture.tokens(), toJson(java.util.Map.of("proof", "invalid")))
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_DEVICE_PROOF"));
		assertOwner(fixture, fixture.user());
		UUID runId = UUID.fromString(run.path("resetRunId").asString());
		String nonce = jdbc.queryForObject("select nonce from keyring_reset_run where run_id=?", String.class, runId);
		String body = toJson(java.util.Map.of("proof", proof(runId, fixture.generation(), nonce, false)));
		perform(post(path + "/ack"), fixture.tokens(), body).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("COMPLETED")).andExpect(jsonPath("$.deviceCommand").doesNotExist());
		assertOwner(fixture, null);
		perform(post(path + "/ack"), fixture.tokens(), body).andExpect(status().isOk());
		perform(post(path + "/ack"), fixture.tokens(), "{\"proof\":\"different\"}")
			.andExpect(status().isConflict());
		perform(get(path), fixture.tokens(), null).andExpect(status().isOk());
		JsonNode other = login("kakao", newSocialId());
		agree(other).andExpect(status().isOk());
		perform(get(path), other, null).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select serial_number from keyring where id=?", String.class, fixture.device()))
			.isEqualTo(fixture.serial());
		assertThat(jdbc.queryForObject("select credential_key_id from keyring where id=?", String.class,
			fixture.device()))
			.isEqualTo("synthetic-key");
		assertThat(jdbc.queryForObject("select credential_public_key from keyring where id=?", String.class,
			fixture.device())).isEqualTo("synthetic-public-key");
		assertThat(jdbc.queryForObject("select count(*) from users where id=?", Long.class, fixture.user()))
			.isEqualTo(1);
		guard.requireNoPendingReset(fixture.device());
	}

	@Test
	void unsupportedProtocolNeverCreatesAResetAndRequestsRequireOwnership() throws Exception {
		Fixture fixture = fixture();
		willReturn(null).given(protocol).issue(any());
		JsonNode result = create(fixture, UUID.randomUUID(), false, 422);
		assertThat(result.path("code").asString()).isEqualTo("POLICY_UNRESOLVED");
		assertOwner(fixture, fixture.user());
		assertThat(jdbc.queryForObject("select count(*) from keyring_reset_run where keyring_id=?", Long.class,
			fixture.device())).isZero();
		JsonNode other = login("kakao", newSocialId());
		agree(other).andExpect(status().isOk());
		perform(post(path(fixture)).header("Idempotency-Key", UUID.randomUUID()), other,
			"{\"discardUnsentConfirmed\":false}").andExpect(status().isNotFound());
		perform(post(path(fixture)), fixture.tokens(), "{\"discardUnsentConfirmed\":false}")
			.andExpect(status().isBadRequest());
		perform(post(path(fixture)).header("Idempotency-Key", UUID.randomUUID()), fixture.tokens(), "{}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void refreshesExpiredCommandWithoutCreatingAnotherReset() throws Exception {
		Fixture fixture = fixture();
		JsonNode initial = create(fixture, UUID.randomUUID(), false, 201);
		String path = path(fixture) + "/" + initial.path("resetRunId").asString() + "/command";
		timeShift.set(java.time.Duration.ofSeconds(301));
		JsonNode renewed = jsonMapper.readTree(perform(post(path), fixture.tokens(), null).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		assertThat(renewed.path("resetRunId")).isEqualTo(initial.path("resetRunId"));
		assertThat(renewed.path("commandExpiresAt")).isNotEqualTo(initial.path("commandExpiresAt"));
		assertOwner(fixture, fixture.user());
		assertThat(jdbc.queryForObject("select count(*) from keyring_reset_run where keyring_id=?", Long.class,
			fixture.device())).isEqualTo(1);
	}

	@Test
	void changedOwnershipGenerationCannotReuseAnUnfinishedCommand() throws Exception {
		Fixture fixture = fixture();
		UUID key = UUID.randomUUID();
		JsonNode run = create(fixture, key, false, 201);
		UUID generation = UUID.randomUUID();
		jdbc.update("update keyring set owner_generation=? where id=?", generation, fixture.device());
		perform(get(path(fixture) + "/" + run.path("resetRunId").asString()), fixture.tokens(), null)
			.andExpect(status().isUnprocessableContent());
		create(fixture, key, false, 422);
		perform(post(path(fixture) + "/" + run.path("resetRunId").asString() + "/ack"), fixture.tokens(),
			"{\"proof\":\"old-generation-proof\"}").andExpect(status().isUnprocessableContent());
		assertThat(jdbc.queryForObject("select owner_generation from keyring where id=?", UUID.class,
			fixture.device())).isEqualTo(generation);
		assertOwner(fixture, fixture.user());
	}

	@Test
	void concurrentRetriesReturnOneDurableExecution() throws Exception {
		Fixture fixture = fixture();
		JsonNode claims = jsonMapper.readTree(Base64.getUrlDecoder()
			.decode(fixture.tokens().path("accessToken").asString().split("\\.")[1]));
		UUID family = UUID.fromString(claims.path("sid").asString());
		UUID key = UUID.randomUUID();
		var executor = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			var first = executor.submit(() -> {
				start.await();
				return service.create(fixture.user(), family, fixture.device(), key, false);
			});
			var second = executor.submit(() -> {
				start.await();
				return service.create(fixture.user(), family, fixture.device(), key, false);
			});
			start.countDown();
			var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
			assertThat(results.stream().filter(KeyringResetService.Created::created).count()).isEqualTo(1);
			assertThat(results.getFirst().run()).isEqualTo(results.getLast().run());
		} finally {
			executor.shutdownNow();
		}
		assertThat(jdbc.queryForObject("select count(*) from keyring_reset_run where keyring_id=?", Long.class,
			fixture.device())).isEqualTo(1);
	}

	private Fixture fixture() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		long user = userIdOf(tokens);
		UUID generation = UUID.randomUUID();
		String serial = "reset-test-" + UUID.randomUUID();
		long device = jdbc.queryForObject("insert into keyring(serial_number,firmware_version,state,owner_user_id,"
			+ "owner_generation,credential_key_id,credential_public_key) "
			+ "values (?,'reset-test','REGISTERED',?,?,'synthetic-key','synthetic-public-key') returning id",
			Long.class, serial, user, generation);
		return new Fixture(tokens, user, device, generation, serial);
	}

	private JsonNode create(Fixture fixture, UUID key, boolean discard, int expected) throws Exception {
		return jsonMapper.readTree(perform(post(path(fixture)).header("Idempotency-Key", key), fixture.tokens(),
			"{\"discardUnsentConfirmed\":" + discard + "}").andExpect(status().is(expected))
			.andReturn().getResponse().getContentAsString());
	}

	private String path(Fixture fixture) {
		return "/api/keyrings/" + fixture.device() + "/reset-runs";
	}

	private void assertOwner(Fixture fixture, Long owner) {
		assertThat(jdbc.queryForObject("select owner_user_id from keyring where id=?", Long.class, fixture.device()))
			.isEqualTo(owner);
	}

	private String proof(UUID runId, UUID generation, String nonce, boolean discard) {
		return "synthetic-proof:" + runId + ":" + generation + ":" + nonce + ":" + discard;
	}

	private record Fixture(JsonNode tokens, long user, long device, UUID generation, String serial) {
	}
}
