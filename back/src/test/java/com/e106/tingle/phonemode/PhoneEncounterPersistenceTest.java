package com.e106.tingle.phonemode;

import static org.assertj.core.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.global.error.BusinessException;

/** PostgreSQL 접수 및 권한 검증. 평가기와 제안 생성은 실행하지 않는다. */
@Testcontainers
class PhoneEncounterPersistenceTest {
	private static final Instant NOW = Instant.parse("2026-11-06T01:00:00Z");
	private static final String TOKEN_KEY = value(32, (byte)7);
	private static final String LEFT_NONCE = value(16, (byte)1);
	private static final String RIGHT_NONCE = value(16, (byte)2);
	private static final String SNAPSHOT_HASH = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

	private static JdbcTemplate jdbc;
	private static TransactionTemplate transaction;
	private static MemberAccessRepository members;

	private MutableClock clock;
	private PhoneEncounterService service;
	private long left;
	private long right;

	@BeforeAll
	static void initializeDisposableDatabase() {
		DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(), POSTGRES.getPassword());
		new ResourceDatabasePopulator(new ClassPathResource("database/schema.sql"),
			new ClassPathResource("database/phone-mode-262.sql")).execute(source);
		jdbc = new JdbcTemplate(source);
		transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty("app.agreement.items[0].type", "TERMS");
		environment.setProperty("app.agreement.items[0].version", "test");
		environment.setProperty("app.agreement.items[0].required", "true");
		members = new MemberAccessRepository(jdbc, environment);
	}

	@BeforeEach
	void createTestAccounts() {
		jdbc.execute("truncate users cascade");
		jdbc.execute("truncate phone_mode_device,phone_mode_token,phone_mode_encounter,phone_mode_submission,"
			+ "phone_mode_evaluation,phone_mode_evaluation_input,phone_mode_request,user_block,"
			+ "settings_snapshot,user_setting");
		clock = new MutableClock();
		left = member(true);
		right = member(true);
		service = new PhoneEncounterService(jdbc, members, clock, left + "," + right, TOKEN_KEY);
	}

	@Test
	void onboardingAndPhoneEnrollmentHaveSeparateRequirements() {
		long incomplete = member(false);
		PhoneEncounterService allowlisted = new PhoneEncounterService(jdbc, members, clock,
			left + "," + incomplete, TOKEN_KEY);
		assertThatThrownBy(() -> transaction.execute(status ->
			allowlisted.register(incomplete, UUID.randomUUID(), UUID.randomUUID(), false)))
			.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> register(member(true), UUID.randomUUID(), UUID.randomUUID(), false))
			.isInstanceOf(BusinessException.class);
		assertThat(members.signupCompleted(left)).isTrue();
		assertThat(members.hasKeyring(left)).isFalse();
		assertThatThrownBy(() -> issue(left, UUID.randomUUID(), UUID.randomUUID()))
			.isInstanceOf(BusinessException.class);
		register(left, UUID.randomUUID(), UUID.randomUUID(), false);
		assertThat(members.signupCompleted(left)).isTrue();
		assertThat(members.hasKeyring(left)).isFalse();
		assertThat(members.signupCompleted(incomplete)).isFalse();
	}

	@Test
	void deviceReplacementAndLostIssuanceResponseKeepTheirOwnBoundaries() {
		UUID installation = UUID.randomUUID();
		UUID registerKey = UUID.randomUUID();
		var first = register(left, registerKey, installation, false);
		assertThat(register(left, registerKey, installation, false).deviceId()).isEqualTo(first.deviceId());
		assertThat(register(left, UUID.randomUUID(), installation, false).deviceId()).isEqualTo(first.deviceId());
		assertThatThrownBy(() -> register(left, registerKey, UUID.randomUUID(), false))
			.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> register(left, UUID.randomUUID(), UUID.randomUUID(), false))
			.isInstanceOf(BusinessException.class);

		UUID issueKey = UUID.randomUUID();
		var token = issue(left, issueKey, first.deviceId());
		assertThat(issue(left, issueKey, first.deviceId())).isEqualTo(token);

		var replacement = register(left, UUID.randomUUID(), UUID.randomUUID(), true);
		assertThat(replacement.deviceId()).isNotEqualTo(first.deviceId());
		assertThatThrownBy(() -> issue(left, issueKey, first.deviceId())).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> issue(left, issueKey, replacement.deviceId())).isInstanceOf(BusinessException.class);
	}

	@Test
	void oneSideWaitsAndReverseSubmissionConfirmsTheSameEncounter() {
		var pair = exchange();
		UUID rightKey = UUID.randomUUID();
		var pending = submitRight(pair, rightKey);
		assertThat(pending.status()).isEqualTo("PENDING_PEER");
		assertThatThrownBy(() -> submit(right, rightKey, pair.right(), LEFT_NONCE, pair.left(), RIGHT_NONCE))
			.isInstanceOf(BusinessException.class).hasMessage("PHONE_MODE_IDEMPOTENCY_CONFLICT");
		assertThat(evaluationRows()).isZero();

		var confirmed = submitLeft(pair, UUID.randomUUID());
		assertThat(confirmed.encounterId()).isEqualTo(pending.encounterId());
		assertThat(confirmed.status()).isEqualTo("CONFIRMED");
		assertThat(confirmed.evaluationStatus()).isEqualTo("UNAVAILABLE");
		assertThat(submitRight(pair, rightKey)).isEqualTo(confirmed);
		assertThat(service.status(left, confirmed.encounterId())).isEqualTo(confirmed);
		assertThat(rows("phone_mode_encounter")).isOne();
		assertThat(rows("phone_mode_submission")).isEqualTo(2);
		assertThat(evaluationRows()).isOne();
	}

	@Test
	void simultaneousSubmissionsCommitOneEncounterAndOneEvaluationRow() throws Exception {
		var pair = exchange();
		currentSettings(left, 1);
		currentSettings(right, 1);
		snapshot(left, 1);
		snapshot(right, 1);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var first = executor.submit(() -> {
				start.await();
				return submitLeft(pair, UUID.randomUUID());
			});
			var second = executor.submit(() -> {
				start.await();
				return submitRight(pair, UUID.randomUUID());
			});
			start.countDown();

			var firstResult = first.get(10, TimeUnit.SECONDS);
			var secondResult = second.get(10, TimeUnit.SECONDS);
			assertThat(firstResult.encounterId()).isEqualTo(secondResult.encounterId());
			assertThat(service.status(left, firstResult.encounterId()).status()).isEqualTo("CONFIRMED");
		}

		assertThat(rows("phone_mode_encounter")).isOne();
		assertThat(rows("phone_mode_submission")).isEqualTo(2);
		assertThat(evaluationRows()).isOne();
		assertThat(rows("phone_mode_evaluation_input")).isEqualTo(2);
	}

	@Test
	void abortedConfirmationRollsBackTheSubmissionAndEvaluationRow() {
		currentSettings(left, 1);
		currentSettings(right, 1);
		snapshot(left, 1);
		snapshot(right, 1);
		var pair = exchange();
		var pending = submitLeft(pair, UUID.randomUUID());
		UUID peerKey = UUID.randomUUID();
		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			assertThat(submitRight(pair, peerKey).status()).isEqualTo("CONFIRMED");
			throw new IllegalStateException("rollback after creating evaluation row");
		})).isInstanceOf(IllegalStateException.class).hasMessage("rollback after creating evaluation row");

		assertThat(service.status(left, pending.encounterId()).status()).isEqualTo("PENDING_PEER");
		assertThat(rows("phone_mode_submission")).isOne();
		assertThat(evaluationRows()).isZero();
		assertThat(rows("phone_mode_evaluation_input")).isZero();
		assertThat(submitRight(pair, peerKey).status()).isEqualTo("CONFIRMED");
		assertThat(evaluationRows()).isOne();
		assertThat(rows("phone_mode_evaluation_input")).isEqualTo(2);
	}

	@Test
	void confirmationPinsTheCurrentSettingsVersionsAndReplayKeepsThem() {
		currentSettings(left, 2);
		currentSettings(right, 5);
		snapshot(left, 1);
		long leftSnapshot = snapshot(left, 2);
		long rightSnapshot = snapshot(right, 5);
		var pair = exchange();
		var pending = submitLeft(pair, UUID.randomUUID());
		assertThat(rows("phone_mode_evaluation_input")).isZero();

		UUID peerKey = UUID.randomUUID();
		var confirmed = submitRight(pair, peerKey);
		assertThat(pinnedInputs(confirmed.encounterId())).containsExactly(
			new PinnedInput(left, leftSnapshot, 2), new PinnedInput(right, rightSnapshot, 5));
		assertThat(confirmed.encounterId()).isEqualTo(pending.encounterId());

		currentSettings(left, 3);
		snapshot(left, 3);
		assertThat(submitRight(pair, peerKey)).isEqualTo(confirmed);
		assertThat(submitRight(pair, UUID.randomUUID())).isEqualTo(confirmed);
		assertThat(pinnedInputs(confirmed.encounterId())).containsExactly(
			new PinnedInput(left, leftSnapshot, 2), new PinnedInput(right, rightSnapshot, 5));
	}

	@Test
	void missingCurrentSnapshotDoesNotUseAnOlderOrFutureSnapshotOrBackfillOnReplay() {
		currentSettings(left, 2);
		currentSettings(right, 1);
		snapshot(left, 1);
		snapshot(left, 3);
		snapshot(right, 1);
		var pair = exchange();
		submitLeft(pair, UUID.randomUUID());
		UUID peerKey = UUID.randomUUID();
		var confirmed = submitRight(pair, peerKey);

		assertThat(confirmed.status()).isEqualTo("CONFIRMED");
		assertThat(confirmed.evaluationStatus()).isEqualTo("UNAVAILABLE");
		assertThat(evaluationReason(confirmed.encounterId())).isEqualTo("SNAPSHOT_UNAVAILABLE");
		assertThat(pinnedInputs(confirmed.encounterId())).isEmpty();
		snapshot(left, 2);
		assertThat(submitRight(pair, peerKey)).isEqualTo(confirmed);
		assertThat(pinnedInputs(confirmed.encounterId())).isEmpty();
		assertThat(evaluationReason(confirmed.encounterId())).isEqualTo("SNAPSHOT_UNAVAILABLE");
	}

	@Test
	void tokensRejectTamperingWrongOwnerSelfExchangeAndReuse() {
		var pair = exchange();
		assertThatThrownBy(() -> submit(left, UUID.randomUUID(), pair.right(), LEFT_NONCE, pair.left(), RIGHT_NONCE))
			.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> submit(left, UUID.randomUUID(), pair.left(), LEFT_NONCE, pair.left(), RIGHT_NONCE))
			.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> submit(left, UUID.randomUUID(), value(32, (byte)9), LEFT_NONCE,
			pair.right(), RIGHT_NONCE)).isInstanceOf(BusinessException.class);
		assertThat(rows("phone_mode_encounter")).isZero();

		var pending = submitLeft(pair, UUID.randomUUID());
		assertThatThrownBy(() -> submit(right, UUID.randomUUID(), pair.right(), LEFT_NONCE, pair.left(), RIGHT_NONCE))
			.isInstanceOf(BusinessException.class).hasMessage("PHONE_MODE_TOKEN_REUSED");
		var active = jdbc.queryForObject("select id from phone_mode_device where user_id=? and active",
			UUID.class, right);
		var fresh = issue(right, UUID.randomUUID(), active);
		assertThatThrownBy(() -> submit(right, UUID.randomUUID(), fresh.token(), RIGHT_NONCE, pair.left(), LEFT_NONCE))
			.isInstanceOf(BusinessException.class).hasMessage("PHONE_MODE_TOKEN_REUSED");
		assertThat(service.status(left, pending.encounterId()).status()).isEqualTo("PENDING_PEER");
		assertThat(rows("phone_mode_encounter")).isOne();
	}

	@Test
	void expiryAndRevocationPreventConfirmation() {
		var pair = exchange();
		var pending = submitLeft(pair, UUID.randomUUID());
		jdbc.update("update users set signup_completed_at=null where id=?", left);
		assertThatThrownBy(() -> submitRight(pair, UUID.randomUUID()))
			.isInstanceOf(BusinessException.class).hasMessage("PHONE_MODE_PAIR_UNAVAILABLE");
		jdbc.update("update users set signup_completed_at=? where id=?", Timestamp.from(NOW), left);
		clock.now = NOW.plusSeconds(301);
		assertThatThrownBy(() -> submitRight(pair, UUID.randomUUID()))
			.isInstanceOf(BusinessException.class).hasMessage("PHONE_MODE_TOKEN_EXPIRED");
		assertThat(service.status(left, pending.encounterId()).status()).isEqualTo("EXPIRED");
		assertThat(evaluationRows()).isZero();
	}

	@Test
	void thirdPartyWithASniffedPeerTokenCannotConsumeIt() {
		long intruder = member(true);
		service = new PhoneEncounterService(jdbc, members, clock, left + "," + right + "," + intruder, TOKEN_KEY);
		var pair = exchange();
		UUID device = register(intruder, UUID.randomUUID(), UUID.randomUUID(), false).deviceId();
		String own = issue(intruder, UUID.randomUUID(), device).token();
		var forged = submit(intruder, UUID.randomUUID(), own, value(16, (byte)3), pair.right(), value(16, (byte)4));
		assertThat(forged.status()).isEqualTo("PENDING_PEER");

		var pending = submitLeft(pair, UUID.randomUUID());
		var confirmed = submitRight(pair, UUID.randomUUID());
		assertThat(confirmed.status()).isEqualTo("CONFIRMED");
		assertThat(confirmed.encounterId()).isEqualTo(pending.encounterId()).isNotEqualTo(forged.encounterId());
	}

	@Test
	void restrictedPairsFollowTheSharedMemberRule() {
		var pair = exchange();
		var pending = submitLeft(pair, UUID.randomUUID());
		long matching = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,status) "
			+ "values (?,?,'WAITING') returning id", Long.class, Math.min(left, right), Math.max(left, right));
		jdbc.update("insert into matching_participant(matching_id,user_id) values (?,?),(?,?)",
			matching, left, matching, right);
		for (long blocker : List.of(left, right)) {
			jdbc.update("update matching_participant set blocked_at=? where matching_id=? and user_id=?",
				Timestamp.from(NOW), matching, blocker);
			assertCannotConfirm(pair);
			jdbc.update("update matching_participant set blocked_at=null where matching_id=?", matching);
		}
		jdbc.update("update matching set status='CLOSED' where id=?", matching);
		assertCannotConfirm(pair);
		assertThat(service.status(left, pending.encounterId()).status()).isEqualTo("PENDING_PEER");
		assertThat(evaluationRows()).isZero();
	}

	private void assertCannotConfirm(Exchange pair) {
		assertThatThrownBy(() -> submitRight(pair, UUID.randomUUID()))
			.isInstanceOf(BusinessException.class).hasMessage("PHONE_MODE_PAIR_UNAVAILABLE");
	}

	private PhoneEncounterService.Registration register(long user, UUID key, UUID installation, boolean replace) {
		return transaction.execute(status -> service.register(user, key, installation, replace));
	}

	private PhoneEncounterService.Token issue(long user, UUID key, UUID device) {
		return transaction.execute(status -> service.issue(user, key, device));
	}

	private PhoneEncounterService.Encounter submitLeft(Exchange exchange, UUID key) {
		return submit(left, key, exchange.left(), LEFT_NONCE, exchange.right(), RIGHT_NONCE);
	}

	private PhoneEncounterService.Encounter submitRight(Exchange exchange, UUID key) {
		return submit(right, key, exchange.right(), RIGHT_NONCE, exchange.left(), LEFT_NONCE);
	}

	private PhoneEncounterService.Encounter submit(long user, UUID key, String ownToken, String ownNonce,
		String peerToken, String peerNonce) {
		return transaction.execute(status -> service.submit(user, key, 1, ownToken, ownNonce, peerToken, peerNonce));
	}

	private Exchange exchange() {
		UUID leftDevice = register(left, UUID.randomUUID(), UUID.randomUUID(), false).deviceId();
		UUID rightDevice = register(right, UUID.randomUUID(), UUID.randomUUID(), false).deviceId();
		return new Exchange(issue(left, UUID.randomUUID(), leftDevice).token(),
			issue(right, UUID.randomUUID(), rightDevice).token());
	}

	private static long member(boolean signedUp) {
		long id = jdbc.queryForObject(
			"insert into users(provider,provider_user_id,status,created_at,signup_completed_at)"
			+ " values ('GOOGLE',?,'ACTIVE',?,?) returning id", Long.class, UUID.randomUUID().toString(),
			Timestamp.from(NOW), signedUp ? Timestamp.from(NOW) : null);
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at)"
			+ " values (?,'TERMS','test',true,?)", id, Timestamp.from(NOW));
		return id;
	}

	private static int rows(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

	private static int evaluationRows() {
		return rows("phone_mode_evaluation");
	}

	private static void currentSettings(long user, long version) {
		jdbc.update("insert into user_setting(user_id,vibration_enabled,led_enabled,settings_version)"
			+ " values (?,true,true,?) on conflict(user_id) do update set settings_version=excluded.settings_version",
			user, version);
	}

	private static long snapshot(long user, long version) {
		return jdbc.queryForObject("insert into settings_snapshot(user_id,settings_version,input_format,input_json,"
			+ "input_hash,captured_at) values (?,?,'settings-source-evidence-v1','{}',?,?) returning id",
			Long.class, user, version, SNAPSHOT_HASH, Timestamp.from(NOW));
	}

	private static List<PinnedInput> pinnedInputs(UUID encounter) {
		return jdbc.query("select user_id,snapshot_id,settings_version from phone_mode_evaluation_input"
			+ " where encounter_id=? order by user_id", (rs, row) ->
				new PinnedInput(rs.getLong(1), rs.getLong(2), rs.getLong(3)), encounter);
	}

	private static String evaluationReason(UUID encounter) {
		return jdbc.queryForObject("select reason from phone_mode_evaluation where encounter_id=?",
			String.class, encounter);
	}

	private static String value(int length, byte fill) {
		byte[] bytes = new byte[length];
		Arrays.fill(bytes, fill);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private record Exchange(String left, String right) {
	}

	private record PinnedInput(long userId, long snapshotId, long version) {
	}

	private static final class MutableClock extends Clock {
		private Instant now = NOW;

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return Clock.fixed(now, zone);
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
