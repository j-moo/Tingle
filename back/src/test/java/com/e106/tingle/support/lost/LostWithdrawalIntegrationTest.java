package com.e106.tingle.support.lost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.auth.SocialProvider;
import com.e106.tingle.auth.dto.LoginRequest;

import tools.jackson.databind.JsonNode;

@SpringBootTest(properties = {"app.withdrawal.enabled=true",
	"app.withdrawal.identity-secret=identity-test-secret-is-at-least-thirty-two-bytes-long",
	"app.withdrawal.receipt-secret=receipt-test-secret-is-at-least-thirty-two-bytes-long",
	"app.withdrawal.scheduling-enabled=false", "app.withdrawal.photo-cleanup-scheduling-enabled=false",
	"app.lost-withdrawal.scheduling-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class LostWithdrawalIntegrationTest extends IntegrationTestSupport {
	private static final Instant NOW = Instant.parse("2026-11-06T08:00:00Z");
	@Autowired
	private LostWithdrawalService service;

	@BeforeEach
	void fixedTime() {
		given(clock.instant()).willReturn(NOW);
	}

	@Test
	void socialReauthenticationReplaysWithoutRotatingOrCallingProviderTwice() throws Exception {
		String subject = newSocialId();
		JsonNode member = login("kakao", subject);
		long device = device(member);
		UUID key = UUID.randomUUID();
		clearInvocations(socialVerifier);
		JsonNode first = reauthenticate(member, subject, device, key);
		JsonNode replay = reauthenticate(member, subject, device, key);
		assertThat(first.path("reauthenticationId").asString())
			.isEqualTo(replay.path("reauthenticationId").asString());
		verify(socialVerifier, times(1)).verify(eq(SocialProvider.KAKAO), any(LoginRequest.class));
		perform(get(me(member, "/lost-keyring-targets")), member, null).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.items[0].deviceId").value(device));
	}

	@Test
	void mismatchedSocialAccountCannotAuthorizeTheOwnersDevice() throws Exception {
		String subject = newSocialId();
		JsonNode owner = login("kakao", subject);
		String otherSubject = newSocialId();
		login("kakao", otherSubject);
		long device = device(owner);
		perform(post(me(owner, "/lost-keyring-reauthentications/kakao"))
			.header("Idempotency-Key", UUID.randomUUID()), owner, toJson(Map.of("deviceId", device,
				"authentication", Map.of("token", "social-" + otherSubject))))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SOCIAL_TOKEN"));
		perform(get(me(owner, "/lost-keyring-targets")), owner, null).andExpect(status().isOk());
	}

	@Test
	void receiptRequiresReviewThenFinalConfirmationBeforeAtomicBlockAndWithdrawal() throws Exception {
		Fixture fixture = fixture();
		JsonNode created = createCase(fixture);
		long caseId = created.path("caseId").asLong();
		String receipt = created.path("receiptToken").asString();
		String withdrawal = created.path("withdrawal").path("withdrawalId").asString();
		complete(fixture, withdrawal).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CASE_STATE_CONFLICT"));
		assertThat(jdbc.queryForObject("select owner_user_id from keyring where id=?", Long.class,
			fixture.device())).isEqualTo(userIdOf(fixture.member()));
		review(fixture, caseId);
		confirm(fixture, caseId).andExpect(status().isOk()).andExpect(jsonPath("$.finalConfirmed").value(true));
		complete(fixture, withdrawal).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.devicePermanentlyBlocked").value(true));
		complete(fixture, withdrawal).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"));
		mockMvc.perform(post("/api/withdrawals/" + withdrawal + "/result")
			.header("X-Withdrawal-Receipt", receipt)).andExpect(status().isOk())
			.andExpect(jsonPath("$.devicePermanentlyBlocked").value(true))
			.andExpect(jsonPath("$.rejoinAvailableAt").value(NOW.plus(Duration.ofHours(72)).toString()));
		assertThat(jdbc.queryForObject("select count(*) from users where id=?", Long.class,
			userIdOf(fixture.member()))).isZero();
		assertThat(jdbc.queryForObject("select permanently_blocked_at is not null and owner_user_id is null "
			+ "and owner_generation is null from keyring where id=?", Boolean.class, fixture.device())).isTrue();
		for (String table : new String[] {"service_case", "service_case_history", "lost_withdrawal_request",
			"lost_case_command"}) {
			String field = "service_case".equals(table) ? "id"
				: "service_case_history".equals(table) ? "service_case_id" : "case_id";
			assertThat(jdbc.queryForObject("select count(*) from " + table + " where " + field + "=?",
				Long.class, caseId)).isZero();
		}
		assertThat(jdbc.queryForObject("select count(*) from operator_audit where target_keyring_id=? "
			+ "and action in ('KEYRING_UNBIND','MEMBER_WITHDRAW') and result_code='SUCCESS' "
			+ "and target_user_id is null", Long.class, fixture.device())).isEqualTo(2);
	}

	@Test
	void normalDeviceOrPendingNormalResetPreventsPartialLostCompletion() throws Exception {
		Fixture fixture = fixture();
		long normal = device(fixture.member());
		JsonNode created = createCase(fixture);
		long caseId = created.path("caseId").asLong();
		String withdrawal = created.path("withdrawal").path("withdrawalId").asString();
		review(fixture, caseId);
		confirm(fixture, caseId).andExpect(status().isOk());
		complete(fixture, withdrawal).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WITHDRAWAL_KEYRING_REGISTERED"));
		assertThat(jdbc.queryForObject("select permanently_blocked_at is null from keyring where id=?",
			Boolean.class, fixture.device())).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from service_case_history where service_case_id=? "
			+ "and event='ACTION_FAILED'", Long.class, caseId)).isEqualTo(1);
		// Synthetic server-reset fixture; this is not a physical device proof.
		jdbc.update("update keyring set owner_user_id=null,owner_generation=null,state='UNBOUND' where id=?", normal);
		resetTable();
		UUID pending = pendingReset(userIdOf(fixture.member()), normal);
		complete(fixture, withdrawal).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WITHDRAWAL_RESET_PENDING"));
		jdbc.update("delete from keyring_reset_run where run_id=?", pending);
		complete(fixture, withdrawal).andExpect(status().isOk());
	}

	@Test
	void pendingResetOfOnlyTheLostTargetIsInvalidatedWithoutInventingProof() throws Exception {
		Fixture fixture = fixture();
		resetTable();
		UUID pending = pendingReset(userIdOf(fixture.member()), fixture.device());
		JsonNode created = createCase(fixture);
		review(fixture, created.path("caseId").asLong());
		confirm(fixture, created.path("caseId").asLong()).andExpect(status().isOk());
		complete(fixture, created.path("withdrawal").path("withdrawalId").asString()).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("select count(*) from keyring_reset_run where run_id=?", Long.class,
			pending)).isZero();
	}

	@Test
	void additionalInfoDeadlineStartsOnlyAtDeliveryAndDoesNotExtendOnReplay() throws Exception {
		Fixture fixture = fixture();
		JsonNode created = createCase(fixture);
		long caseId = created.path("caseId").asLong();
		JsonNode needs = json(action(fixture, caseId, "REQUEST_INFO", "Please confirm the selected device.")
			.andExpect(status().isOk()));
		long infoId = needs.path("infoRequestId").asLong();
		assertThat(needs.path("replyDueAt").isNull()).isTrue();
		given(clock.instant()).willReturn(NOW.plus(Duration.ofDays(20)));
		service.purge();
		assertThat(jdbc.queryForObject("select status::text from service_case where id=?", String.class, caseId))
			.isEqualTo("NEEDS_INFO");
		JsonNode current = login("kakao", fixture.subject());
		String ackPath = me(current, "/lost-keyring-cases/" + caseId + "/info-requests/" + infoId
			+ "/acknowledgement");
		perform(post(ackPath), current, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.replyDueAt").value(NOW.plus(Duration.ofDays(34)).toString()));
		given(clock.instant()).willReturn(NOW.plus(Duration.ofDays(20)).plusSeconds(1));
		perform(post(ackPath), current, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.replyDueAt").value(NOW.plus(Duration.ofDays(34)).toString()));
		given(clock.instant()).willReturn(NOW.plus(Duration.ofDays(35)));
		service.purge();
		assertThat(jdbc.queryForObject("select status::text from service_case where id=?", String.class, caseId))
			.isEqualTo("NO_RESPONSE");
		assertThat(jdbc.queryForObject("select closed_at from service_case where id=?",
			java.sql.Timestamp.class, caseId).toInstant()).isEqualTo(NOW.plus(Duration.ofDays(34)));
		given(clock.instant()).willReturn(NOW.plus(Duration.ofDays(64)).plusSeconds(1));
		JsonNode renewed = login("kakao", fixture.subject());
		perform(get(me(renewed, "/lost-keyring-cases/" + caseId)), renewed, null)
			.andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select count(*) from service_case where id=?", Long.class, caseId))
			.isEqualTo(1);
		service.purge();
		assertThat(jdbc.queryForObject("select count(*) from service_case where id=?", Long.class, caseId)).isZero();
	}

	@Test
	void freshReauthenticationAfterReloginInvalidatesOldReviewAndConfirmation() throws Exception {
		Fixture fixture = fixture();
		JsonNode created = createCase(fixture);
		long caseId = created.path("caseId").asLong();
		review(fixture, caseId);
		confirm(fixture, caseId).andExpect(status().isOk());
		JsonNode current = login("kakao", fixture.subject());
		JsonNode grant = reauthenticate(current, fixture.subject(), fixture.device(), UUID.randomUUID());
		JsonNode refreshed = json(perform(post(me(current, "/lost-keyring-cases/" + caseId + "/reauthentication")),
			current, toJson(Map.of("reauthenticationId", grant.path("reauthenticationId").asString())))
			.andExpect(status().isOk()));
		assertThat(refreshed.path("operatorVerified").asBoolean()).isFalse();
		assertThat(refreshed.path("finalConfirmed").asBoolean()).isFalse();
		assertThat(refreshed.path("withdrawal").path("withdrawalId").asString())
			.isEqualTo(created.path("withdrawal").path("withdrawalId").asString());
		complete(fixture, created.path("withdrawal").path("withdrawalId").asString())
			.andExpect(status().isConflict());
	}

	@Test
	void currentOwnershipAndFreshReauthenticationAreRequiredForVerification() throws Exception {
		Fixture fixture = fixture();
		JsonNode created = createCase(fixture);
		long caseId = created.path("caseId").asLong();
		action(fixture, caseId, "REVIEW", null).andExpect(status().isOk());
		given(clock.instant()).willReturn(NOW.plus(Duration.ofMinutes(11)));
		action(fixture, caseId, "VERIFY", null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
	}

	@Test
	void nonOperatorAndOtherMembersCannotReadOrHandleTheInquiry() throws Exception {
		Fixture fixture = fixture();
		long caseId = createCase(fixture).path("caseId").asLong();
		JsonNode other = login("kakao", newSocialId());
		perform(get(me(other, "/lost-keyring-cases/" + caseId)), other, null).andExpect(status().isNotFound());
		perform(get("/api/admin/v1/lost-keyring-cases/" + caseId), other, null)
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/users/1/lost-keyring-targets")).andExpect(status().isUnauthorized());
	}

	@Test
	void cancellationIsIdempotentAndNeverBlocksTheDeviceOrWithdrawsTheMember() throws Exception {
		Fixture fixture = fixture();
		JsonNode created = createCase(fixture);
		long caseId = created.path("caseId").asLong();
		String path = me(fixture.member(), "/lost-keyring-cases/" + caseId + "/cancel");
		for (int retry = 0; retry < 2; retry++) {
			perform(post(path), fixture.member(), null).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"))
				.andExpect(jsonPath("$.closedAt").value(NOW.toString()))
				.andExpect(jsonPath("$.withdrawal").isEmpty());
		}
		mockMvc.perform(post("/api/withdrawals/"
			+ created.path("withdrawal").path("withdrawalId").asString() + "/result")
			.header("X-Withdrawal-Receipt", created.path("receiptToken").asString()))
			.andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select permanently_blocked_at is null and owner_user_id=? "
			+ "from keyring where id=?", Boolean.class, userIdOf(fixture.member()), fixture.device())).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from users where id=?", Long.class,
			userIdOf(fixture.member()))).isEqualTo(1);
		assertThat(createCase(fixture).path("caseId").asLong()).isNotEqualTo(caseId);
	}

	@Test
	void rejectionReplayDoesNotExtendRetentionOrCopyTheReplyIntoSecurityAudit() throws Exception {
		Fixture fixture = fixture();
		JsonNode created = createCase(fixture);
		long caseId = created.path("caseId").asLong();
		UUID key = UUID.randomUUID();
		String body = toJson(Map.of("action", "REJECT", "message", "Please check the selected keyring."));
		for (int retry = 0; retry < 2; retry++) {
			perform(post("/api/admin/v1/lost-keyring-cases/" + caseId + "/actions")
				.header("Idempotency-Key", key), fixture.operator(), body).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"))
				.andExpect(jsonPath("$.closedAt").value(NOW.toString()));
		}
		assertThat(jdbc.queryForObject("select count(*) from service_case_history where service_case_id=? "
			+ "and message='Please check the selected keyring.'", Long.class, caseId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from operator_audit where target_service_case_id=? "
			+ "and reason='Please check the selected keyring.'", Long.class, caseId)).isZero();
		assertThat(jdbc.queryForObject("select permanently_blocked_at is null from keyring where id=?",
			Boolean.class, fixture.device())).isTrue();
		complete(fixture, created.path("withdrawal").path("withdrawalId").asString())
			.andExpect(status().isNotFound());
		given(clock.instant()).willReturn(NOW.plus(Duration.ofHours(720)));
		JsonNode member = login("kakao", fixture.subject());
		String operatorSubject = jdbc.queryForObject("select provider_user_id from users where id=?",
			String.class, userIdOf(fixture.operator()));
		JsonNode operator = login("kakao", operatorSubject);
		perform(get(me(member, "/lost-keyring-cases/" + caseId)), member, null)
			.andExpect(status().isNotFound());
		perform(get("/api/admin/v1/lost-keyring-cases/" + caseId), operator, null)
			.andExpect(status().isNotFound());
		JsonNode memberList = json(perform(get(me(member, "/lost-keyring-cases")), member, null)
			.andExpect(status().isOk()));
		JsonNode operatorList = json(perform(get("/api/admin/v1/lost-keyring-cases"), operator, null)
			.andExpect(status().isOk()));
		for (JsonNode list : new JsonNode[] {memberList, operatorList}) {
			for (JsonNode item : list.path("items")) {
				assertThat(item.path("caseId").asLong()).isNotEqualTo(caseId);
			}
		}
		assertThat(jdbc.queryForObject("select count(*) from service_case where id=?", Long.class, caseId))
			.isEqualTo(1);
	}

	@Test
	void repliesReplayWithoutReopeningADeadlineOrTreatingOperatorDelayAsNoResponse() throws Exception {
		Fixture fixture = fixture();
		long caseId = createCase(fixture).path("caseId").asLong();
		JsonNode needs = json(action(fixture, caseId, "REQUEST_INFO", "Confirm the selected device.")
			.andExpect(status().isOk()));
		long infoId = needs.path("infoRequestId").asLong();
		perform(post(me(fixture.member(), "/lost-keyring-cases/" + caseId + "/info-requests/" + infoId
			+ "/acknowledgement")), fixture.member(), null).andExpect(status().isOk());
		UUID key = UUID.randomUUID();
		String body = toJson(Map.of("infoRequestId", infoId, "message", "The selected keyring is correct."));
		for (int retry = 0; retry < 2; retry++) {
			perform(post(me(fixture.member(), "/lost-keyring-cases/" + caseId + "/replies"))
				.header("Idempotency-Key", key), fixture.member(), body).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REVIEWING"))
				.andExpect(jsonPath("$.replyDueAt").isEmpty());
		}
		given(clock.instant()).willReturn(NOW.plus(Duration.ofDays(15)));
		service.purge();
		assertThat(jdbc.queryForObject("select status::text from service_case where id=?", String.class, caseId))
			.isEqualTo("REVIEWING");
		assertThat(jdbc.queryForObject("select count(*) from service_case_history where service_case_id=? "
			+ "and event='USER_REPLIED'", Long.class, caseId)).isEqualTo(1);
	}

	@Test
	void newProofAtTheSameClockInstantInvalidatesGatesAndOldConfirmationReplayCannotRestoreThem() throws Exception {
		Fixture fixture = fixture();
		JsonNode created = createCase(fixture);
		long caseId = created.path("caseId").asLong();
		review(fixture, caseId);
		UUID oldConfirmation = UUID.randomUUID();
		String confirmPath = me(fixture.member(), "/lost-keyring-cases/" + caseId + "/confirmation");
		perform(post(confirmPath).header("Idempotency-Key", oldConfirmation), fixture.member(), target(fixture))
			.andExpect(status().isOk()).andExpect(jsonPath("$.finalConfirmed").value(true));
		JsonNode grant = reauthenticate(fixture.member(), fixture.subject(), fixture.device(), UUID.randomUUID());
		String refreshPath = me(fixture.member(), "/lost-keyring-cases/" + caseId + "/reauthentication");
		String refreshBody = toJson(Map.of("reauthenticationId", grant.path("reauthenticationId").asString()));
		perform(post(refreshPath), fixture.member(), refreshBody).andExpect(status().isOk())
			.andExpect(jsonPath("$.operatorVerified").value(false))
			.andExpect(jsonPath("$.finalConfirmed").value(false));
		perform(post(confirmPath).header("Idempotency-Key", oldConfirmation), fixture.member(), target(fixture))
			.andExpect(status().isOk()).andExpect(jsonPath("$.finalConfirmed").value(false));
		action(fixture, caseId, "VERIFY", null).andExpect(status().isOk());
		confirm(fixture, caseId).andExpect(status().isOk());
		perform(post(refreshPath), fixture.member(), refreshBody).andExpect(status().isOk())
			.andExpect(jsonPath("$.operatorVerified").value(true))
			.andExpect(jsonPath("$.finalConfirmed").value(true));
	}

	@Test
	void anOpenCaseCanRecoverItsExpired30DayReceiptOnlyWithFreshProofAndNewGates() throws Exception {
		Fixture fixture = fixture();
		JsonNode created = createCase(fixture);
		long caseId = created.path("caseId").asLong();
		String previous = created.path("withdrawal").path("withdrawalId").asString();
		given(clock.instant()).willReturn(NOW.plus(Duration.ofDays(31)));
		mockMvc.perform(post("/api/withdrawals/" + previous + "/result")
			.header("X-Withdrawal-Receipt", created.path("receiptToken").asString()))
			.andExpect(status().is4xxClientError())
			.andExpect(jsonPath("$.code").value("WITHDRAWAL_OPERATION_EXPIRED"));
		JsonNode member = login("kakao", fixture.subject());
		JsonNode grant = reauthenticate(member, fixture.subject(), fixture.device(), UUID.randomUUID());
		JsonNode refreshed = json(perform(post(me(member, "/lost-keyring-cases/" + caseId + "/reauthentication")),
			member, toJson(Map.of("reauthenticationId", grant.path("reauthenticationId").asString())))
			.andExpect(status().isOk()));
		assertThat(refreshed.path("withdrawal").path("withdrawalId").asString()).isNotEqualTo(previous);
		assertThat(refreshed.path("operatorVerified").asBoolean()).isFalse();
		assertThat(refreshed.path("finalConfirmed").asBoolean()).isFalse();
		assertThat(jdbc.queryForObject("select status::text from service_case where id=?", String.class, caseId))
			.isEqualTo("RECEIVED");
	}

	@Test
	void anElapsedReplyDeadlineCannotBeResetByAnotherOperatorInfoRequestBeforeThePurgerRuns() throws Exception {
		Fixture fixture = fixture();
		long caseId = createCase(fixture).path("caseId").asLong();
		JsonNode needs = json(action(fixture, caseId, "REQUEST_INFO", "Please confirm the selected device.")
			.andExpect(status().isOk()));
		long infoId = needs.path("infoRequestId").asLong();
		perform(post(me(fixture.member(), "/lost-keyring-cases/" + caseId + "/info-requests/" + infoId
			+ "/acknowledgement")), fixture.member(), null).andExpect(status().isOk());
		String operatorSubject = jdbc.queryForObject("select provider_user_id from users where id=?",
			String.class, userIdOf(fixture.operator()));
		Instant deadline = NOW.plus(Duration.ofDays(14));
		given(clock.instant()).willReturn(deadline);
		JsonNode renewed = login("kakao", operatorSubject);
		perform(post("/api/admin/v1/lost-keyring-cases/" + caseId + "/actions")
			.header("Idempotency-Key", UUID.randomUUID()), renewed,
			toJson(Map.of("action", "REQUEST_INFO", "message", "A replacement question must not reset the deadline.")))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_STATE_CONFLICT"));
		assertThat(jdbc.queryForObject("select reply_due_at from service_case where id=?",
			java.sql.Timestamp.class, caseId).toInstant()).isEqualTo(deadline);
		assertThat(jdbc.queryForObject("select info_request_id from lost_withdrawal_request where case_id=?",
			Long.class, caseId)).isEqualTo(infoId);
		assertThat(jdbc.queryForObject("select status::text from service_case where id=?", String.class, caseId))
			.isEqualTo("NEEDS_INFO");
		assertThat(jdbc.queryForObject("select count(*) from service_case_history where service_case_id=? "
			+ "and event='INFO_REQUESTED'", Long.class, caseId)).isEqualTo(1);
		service.purge();
		assertThat(jdbc.queryForObject("select status::text from service_case where id=?", String.class, caseId))
			.isEqualTo("NO_RESPONSE");
	}

	private Fixture fixture() throws Exception {
		String subject = newSocialId();
		JsonNode member = login("kakao", subject);
		JsonNode operator = login("kakao", newSocialId());
		jdbc.update("update users set is_operator=true where id=?", userIdOf(operator));
		long device = device(member);
		UUID generation = jdbc.queryForObject("select owner_generation from keyring where id=?", UUID.class, device);
		return new Fixture(member, operator, subject, device, generation);
	}

	private long device(JsonNode member) throws Exception {
		long device = bindKeyring(userIdOf(member));
		jdbc.update("update keyring set owner_generation=? where id=?", UUID.randomUUID(), device);
		return device;
	}

	private JsonNode createCase(Fixture fixture) throws Exception {
		JsonNode grant = reauthenticate(fixture.member(), fixture.subject(), fixture.device(), UUID.randomUUID());
		UUID key = UUID.randomUUID();
		String body = toJson(Map.of("reauthenticationId", grant.path("reauthenticationId").asString(),
			"reason", "The selected keyring has been lost."));
		JsonNode created = json(perform(post(me(fixture.member(), "/lost-keyring-cases"))
			.header("Idempotency-Key", key), fixture.member(), body).andExpect(status().isOk()));
		perform(post(me(fixture.member(), "/lost-keyring-cases")).header("Idempotency-Key", key),
			fixture.member(), body).andExpect(status().isOk())
			.andExpect(jsonPath("$.caseId").value(created.path("caseId").asLong()));
		return created;
	}

	private JsonNode reauthenticate(JsonNode member, String subject, long device, UUID key) throws Exception {
		return json(perform(post(me(member, "/lost-keyring-reauthentications/kakao"))
			.header("Idempotency-Key", key), member, toJson(Map.of("deviceId", device,
				"authentication", Map.of("token", "social-" + subject)))).andExpect(status().isOk()));
	}

	private void review(Fixture fixture, long caseId) throws Exception {
		action(fixture, caseId, "REVIEW", null).andExpect(status().isOk());
		action(fixture, caseId, "VERIFY", null).andExpect(status().isOk());
	}

	private ResultActions action(Fixture fixture, long caseId, String action, String message) throws Exception {
		String body = action.equals("VERIFY")
			? toJson(Map.of("action", action, "deviceId", fixture.device(), "ownerGeneration", fixture.generation()))
			: message == null ? toJson(Map.of("action", action)) : toJson(Map.of("action", action, "message", message));
		return perform(post("/api/admin/v1/lost-keyring-cases/" + caseId + "/actions")
			.header("Idempotency-Key", UUID.randomUUID()), fixture.operator(), body);
	}

	private ResultActions confirm(Fixture fixture, long caseId) throws Exception {
		return perform(post(me(fixture.member(), "/lost-keyring-cases/" + caseId + "/confirmation"))
			.header("Idempotency-Key", UUID.randomUUID()), fixture.member(), target(fixture));
	}

	private ResultActions complete(Fixture fixture, String withdrawal) throws Exception {
		return perform(post("/api/admin/v1/lost-keyring-cases/withdrawals/" + withdrawal + "/execute"),
			fixture.operator(), target(fixture));
	}

	private String target(Fixture fixture) throws Exception {
		return toJson(Map.of("deviceId", fixture.device(), "ownerGeneration", fixture.generation(), "confirmed", true));
	}

	private JsonNode json(ResultActions result) throws Exception {
		return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private void resetTable() throws Exception {
		jdbc.execute(Files.readString(Path.of("src/main/resources/database/keyring-reset-249.sql"))
			.replace("create table keyring_reset_run", "create table if not exists keyring_reset_run"));
	}

	private UUID pendingReset(long userId, long deviceId) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into keyring_reset_run(run_id,keyring_id,owner_user_id,owner_generation,request_key,"
			+ "discard_unsent,nonce,command,created_at,expires_at,from_ownership_epoch,request_hash,challenge_hash) "
			+ "values(?,?,?,?,?,true,'synthetic-nonce','synthetic-command',?,?,0,'hash','hash')", id, deviceId,
			userId, UUID.randomUUID(), UUID.randomUUID(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
		return id;
	}

	private record Fixture(JsonNode member, JsonNode operator, String subject, long device, UUID generation) {
	}
}
