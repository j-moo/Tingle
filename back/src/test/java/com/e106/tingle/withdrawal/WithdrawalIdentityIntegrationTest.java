package com.e106.tingle.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.auth.SocialProvider;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;

import tools.jackson.databind.JsonNode;

/** Uses real PostgreSQL and verified-account fixtures; it does not contact a social provider. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class WithdrawalIdentityIntegrationTest extends IntegrationTestSupport {
	private static final Instant NOW = Instant.parse("2026-11-06T06:00:00Z");

	@Autowired
	private WithdrawalIdentityService identities;
	@Autowired
	private UserRepository users;
	@Autowired
	private MemberAccessRepository members;
	@Autowired
	private TransactionTemplate transactions;

	@BeforeEach
	void fixTime() {
		given(clock.instant()).willReturn(NOW);
	}

	@Test
	void restrictsVerifiedSocialLoginForExactly72HoursWithoutExtendingDeadline() throws Exception {
		String subject = newSocialId();
		JsonNode original = login("kakao", subject);
		long formerId = userIdOf(original);
		withdrawIdentity(formerId, true);
		Instant until = NOW.plus(Duration.ofHours(72));
		for (Instant attempt : new Instant[] {NOW.plusSeconds(1), until.minusNanos(1)}) {
			given(clock.instant()).willReturn(attempt);
			mockMvc.perform(post("/api/auth/kakao").contentType(MediaType.APPLICATION_JSON)
					.content("{\"token\":\"social-" + subject + "\"}"))
				.andExpect(status().isConflict()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.code").value("REJOIN_RESTRICTED"))
				.andExpect(jsonPath("$.rejoinAvailableAt").value(until.toString()));
		}
		assertThat(jdbc.queryForObject("select rejoin_available_at from withdrawal_cooldown "
			+ "where provider='KAKAO' and account_hash=?", Timestamp.class,
			accountHash("cooldown-v1", SocialProvider.KAKAO, subject)).toInstant()).isEqualTo(until);
		given(clock.instant()).willReturn(until);
		assertThat(userIdOf(login("kakao", subject))).isNotEqualTo(formerId);
	}

	@Test
	void cooldownContainsOnlyPurposeSpecificMinimumFieldsAndOtherProvidersRemainIndependent() throws Exception {
		String subject = newSocialId();
		withdrawIdentity(userIdOf(login("kakao", subject)), true);
		assertThat(userIdOf(login("google", subject))).isPositive();
		assertThat(jdbc.queryForList("select column_name from information_schema.columns "
			+ "where table_schema=current_schema() and table_name='withdrawal_cooldown'", String.class))
			.containsExactlyInAnyOrder("provider", "account_hash", "rejoin_available_at");
		assertThat(jdbc.queryForList("select account_hash from withdrawal_cooldown", String.class))
			.allMatch(hash -> hash.matches("[a-f0-9]{64}") && !hash.equals(subject));
	}

	@Test
	void archivedBlockAppliesToNewIdButExpiresWithoutWaitingForPurge() throws Exception {
		String subject = newSocialId();
		long oldId = userIdOf(login("kakao", subject));
		long partner = userIdOf(login("google", newSocialId()));
		jdbc.update("insert into user_block(id,owner_id,target_id,matching_id,created_at) values(?,?,?,?,?)",
			UUID.randomUUID(), oldId, partner, 999998L, Timestamp.from(NOW));
		withdrawIdentity(oldId, true);
		jdbc.update("delete from user_block where owner_id=?", oldId);
		given(clock.instant()).willReturn(NOW.plus(Duration.ofHours(72)));
		long newId = userIdOf(login("kakao", subject));
		assertThat(members.restrictedPair(newId, partner)).isTrue();
		assertThat(members.restrictedPair(partner, newId)).isTrue();
		given(clock.instant()).willReturn(Instant.parse("2027-11-06T06:00:00Z"));
		assertThat(members.restrictedPair(newId, partner)).isFalse();
		identities.purgeExpired();
		assertThat(jdbc.queryForObject("select count(*) from withdrawal_relation where expires_at<=?",
			Long.class, Timestamp.from(clock.instant()))).isZero();
	}

	@Test
	void reWithdrawalDoesNotExtendArchivedRelationshipDeadline() throws Exception {
		String firstSubject = newSocialId();
		long first = userIdOf(login("kakao", firstSubject));
		long second = userIdOf(login("google", newSocialId()));
		jdbc.update("insert into user_block(id,owner_id,target_id,matching_id,created_at) values(?,?,?,?,?)",
			UUID.randomUUID(), first, second, 999997L, Timestamp.from(NOW));
		withdrawIdentity(first, false);
		String hash = accountHash("relation-v1", SocialProvider.KAKAO, firstSubject);
		Map<String, Object> archived = jdbc.queryForMap("select * from withdrawal_relation "
			+ "where first_hash=? or second_hash=?", hash, hash);
		given(clock.instant()).willReturn(NOW.plus(Duration.ofDays(20)));
		withdrawIdentity(second, false);
		assertThat(jdbc.queryForMap("select * from withdrawal_relation where first_hash=? and second_hash=?",
			archived.get("first_hash"), archived.get("second_hash"))).isEqualTo(archived);
	}

	@Test
	void missingOrShortConfiguredKeyPreventsStartup() {
		for (String secret : new String[] {"", "short"}) {
			assertThatThrownBy(() -> new WithdrawalIdentityService(jdbc, users, clock, secret))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("WITHDRAWAL_IDENTITY_SECRET");
		}
	}

	@Test
	void replacingWithAnotherValidLengthKeyDoesNotBypassCommittedCooldown() throws Exception {
		String subject = newSocialId();
		withdrawIdentity(userIdOf(login("kakao", subject)), true);
		WithdrawalIdentityService replaced = new WithdrawalIdentityService(jdbc, users, clock,
			"another-valid-length-key-must-not-bypass-existing-records");
		assertThatThrownBy(() -> transactions.execute(status ->
			replaced.provisionMember(SocialProvider.KAKAO, subject)))
			.hasMessage("POLICY_UNRESOLVED");
		assertThat(users.findByProviderAndProviderSubject(SocialProvider.KAKAO, subject)).isEmpty();
	}

	@Test
	void missingKeyMetadataWithLiveRestrictionsFailsClosedWithoutReinitialization() throws Exception {
		String subject = newSocialId();
		withdrawIdentity(userIdOf(login("kakao", subject)), true);
		String fingerprint = jdbc.queryForObject("select key_hash from withdrawal_identity_key where id=1",
			String.class);
		jdbc.update("delete from withdrawal_identity_key where id=1");
		try {
			assertThatThrownBy(() -> identities.provisionMember(SocialProvider.KAKAO, subject))
				.hasMessage("POLICY_UNRESOLVED");
			assertThat(jdbc.queryForObject("select count(*) from withdrawal_identity_key", Long.class)).isZero();
		} finally {
			jdbc.update("insert into withdrawal_identity_key(id,key_hash) values(1,?)", fingerprint);
		}
	}

	@Test
	void independentlyNewBlockAfterOldExpiryGetsItsOwnDeadlineEvenWhenPurgeWasDelayed() throws Exception {
		String subject = newSocialId();
		long first = userIdOf(login("kakao", subject));
		long partner = userIdOf(login("google", newSocialId()));
		jdbc.update("insert into user_block(id,owner_id,target_id,matching_id,created_at) values(?,?,?,?,?)",
			UUID.randomUUID(), first, partner, 999996L, Timestamp.from(NOW));
		withdrawIdentity(first, true);
		jdbc.update("delete from user_block where owner_id=?", first);
		Instant again = Instant.parse("2028-02-29T06:00:00Z");
		given(clock.instant()).willReturn(again);
		long rejoined = userIdOf(login("kakao", subject));
		jdbc.update("insert into user_block(id,owner_id,target_id,matching_id,created_at) values(?,?,?,?,?)",
			UUID.randomUUID(), partner, rejoined, 999995L, Timestamp.from(again));
		withdrawIdentity(rejoined, true);
		String hash = accountHash("relation-v1", SocialProvider.KAKAO, subject);
		assertThat(jdbc.queryForObject("select expires_at from withdrawal_relation "
			+ "where first_hash=? or second_hash=?", Timestamp.class, hash, hash).toInstant())
			.isEqualTo(Instant.parse("2029-02-28T06:00:00Z"));
	}

	private void withdrawIdentity(long userId, boolean deleteMember) {
		User user = users.findById(userId).orElseThrow();
		transactions.executeWithoutResult(status -> {
			identities.lockIdentity(user);
			users.findByIdForUpdate(userId).orElseThrow();
			identities.onWithdrawal(user, clock.instant());
			if (deleteMember) {
				jdbc.update("delete from users where id=?", userId);
			}
		});
	}

	private static String accountHash(String purpose, SocialProvider provider, String subject)
		throws GeneralSecurityException {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("identity-test-secret-is-at-least-thirty-two-bytes-long"
			.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal((purpose + "\0" + provider.name() + "\0" + subject)
			.getBytes(StandardCharsets.UTF_8)));
	}
}
