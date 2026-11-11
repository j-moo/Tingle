package com.e106.tingle.encounter.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.SharedPostgres;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.settings.snapshot.SettingsChangeCoordinator;
import com.e106.tingle.user.MemberWithdrawalCleanup;
import com.e106.tingle.user.MemberWithdrawalDeletionInvalidator;
import com.e106.tingle.user.ProfilePhotoService;
import com.e106.tingle.user.UnderAgeWithdrawalService;

/** 기능 설정과 무관한 탈퇴 삭제 의무를 일회용 PostgreSQL에서 검증한다. */
class KeyringPeerDataRetirementTest {
	private static final Instant NOW = Instant.parse("2026-11-06T08:00:00.123456Z");
	private static final JdbcConnectionDetails POSTGRES = SharedPostgres.newDatabase();
	private SingleConnectionDataSource source;
	private JdbcTemplate jdbc;
	private TransactionTemplate transactions;
	private KeyringPeerDataRetirement retirement;
	private long owner;
	private long peer;

	@BeforeEach
	void schema() throws Exception {
		var base = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		var connection = base.getConnection();
		String schema = "privacy_" + UUID.randomUUID().toString().replace("-", "");
		try (var statement = connection.createStatement()) {
			statement.execute("create schema " + schema);
		}
		connection.setSchema(schema);
		source = new SingleConnectionDataSource(connection, true);
		new ResourceDatabasePopulator(new ClassPathResource("database/schema.sql")).execute(source);
		jdbc = new JdbcTemplate(source);
		transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
		retirement = new KeyringPeerDataRetirement(jdbc);
		owner = member();
		peer = member();
	}

	@AfterEach
	void close() {
		source.destroy();
	}

	@Test
	void missingOptionalTablesAreSafeWhenTheFeatureWasNeverInstalled() {
		transactions.executeWithoutResult(status -> retirement.retireForWithdrawal(owner, NOW));
		assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(2);
	}

	@Test
	void retiresBothParticipantCopiesIdempotentlyWithoutTouchingUnrelatedEvidenceOrLedger() {
		optionalTables();
		UUID own = evidence(owner, peer);
		UUID otherCopy = evidence(peer, owner);
		UUID unrelated = evidence(peer, peer);
		jdbc.execute("create table keyring_peer_command(command_id uuid primary key,command_json text)");
		jdbc.update("insert into keyring_peer_command values (?,?)", UUID.randomUUID(), "identifier-only");
		transactions.executeWithoutResult(status -> {
			retirement.retireForWithdrawal(owner, NOW);
			retirement.retireForWithdrawal(owner, NOW.plusSeconds(1));
		});
		for (UUID id : new UUID[] {own, otherCopy}) {
			assertRetired(id);
		}
		assertThat(jdbc.queryForObject("select retired from keyring_peer_encounter where receipt_id=?",
			Boolean.class, unrelated)).isFalse();
		assertThat(jdbc.queryForObject("select command_json from keyring_peer_command", String.class))
			.isEqualTo("identifier-only");
	}

	@Test
	void closesOnlyTheDepartingOwnersOpenHistoryAtTheExactObservedTime() {
		optionalTables();
		UUID open = history(owner, NOW.minusSeconds(10), null);
		UUID closed = history(owner, NOW.minusSeconds(30), NOW.minusSeconds(20));
		UUID unrelated = history(peer, NOW.minusSeconds(10), null);
		transactions.executeWithoutResult(status -> retirement.retireForWithdrawal(owner, NOW));
		assertThat(end(open)).isEqualTo(NOW);
		assertThat(end(closed)).isEqualTo(NOW.minusSeconds(20));
		assertThat(end(unrelated)).isNull();
	}

	@Test
	void inconsistentFutureHistoryFailsAndRollsBackInsteadOfInventingItsStart() {
		optionalTables();
		UUID id = evidence(owner, peer);
		UUID history = history(owner, NOW.plusSeconds(1), null);
		assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
			retirement.retireForWithdrawal(owner, NOW))).isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.POLICY_UNRESOLVED));
		assertThat(end(history)).isNull();
		assertThat(jdbc.queryForObject("select raw_payload from keyring_peer_encounter where receipt_id=?",
			byte[].class, id)).containsExactly((byte)1);
	}

	@Test
	void surroundingWithdrawalRollbackRestoresOriginalAndOpenOwnershipTogether() {
		optionalTables();
		UUID id = evidence(peer, owner);
		UUID history = history(owner, NOW.minusSeconds(10), null);
		transactions.executeWithoutResult(status -> {
			retirement.retireForWithdrawal(owner, NOW);
			status.setRollbackOnly();
		});
		assertThat(end(history)).isNull();
		assertThat(jdbc.queryForObject("select retired from keyring_peer_encounter where receipt_id=?",
			Boolean.class, id)).isFalse();
		assertThat(jdbc.queryForObject("select envelope_hash from keyring_peer_encounter where receipt_id=?",
			String.class, id)).isEqualTo("original-hash");
	}

	@Test
	void actualUnderAgeWithdrawalPurgesBothCopiesAndClosesHistoryBeforeRemovingTheMember() {
		optionalTables();
		UUID own = evidence(owner, peer);
		UUID other = evidence(peer, owner);
		UUID history = history(owner, NOW.minusSeconds(10), null);
		var service = new UnderAgeWithdrawalService(jdbc, mock(ProfilePhotoService.class),
			Clock.fixed(NOW, ZoneOffset.UTC), retirement);
		transactions.executeWithoutResult(status -> service.withdraw(owner));
		assertRetired(own);
		assertRetired(other);
		assertThat(end(history)).isEqualTo(NOW);
		assertThat(jdbc.queryForObject("select count(*) from users where id=?", Long.class, owner)).isZero();
	}

	@Test
	void normalCleanupIncludesPeerOnlyParticipantsInTheUserLockSetAndPurgesBothCopies() {
		optionalTables();
		UUID own = evidence(owner, peer);
		UUID other = evidence(peer, owner);
		UUID history = history(owner, NOW.minusSeconds(10), null);
		var cleanup = new MemberWithdrawalCleanup(jdbc, mock(SettingsChangeCoordinator.class),
			mock(MemberWithdrawalDeletionInvalidator.class), retirement);
		assertThat(cleanup.relatedMemberIds(owner)).containsExactly(owner, peer);
		transactions.executeWithoutResult(status -> {
			for (long actor : cleanup.relatedMemberIds(owner)) {
				jdbc.queryForObject("select id from users where id=? for update", Long.class, actor);
			}
			cleanup.cleanupUnderLocks(owner, NOW);
		});
		assertRetired(own);
		assertRetired(other);
		assertThat(end(history)).isEqualTo(NOW);
		assertThat(jdbc.queryForObject("select count(*) from users where id=?", Long.class, owner)).isZero();
	}

	@Test
	void failedNormalWithdrawalRestoresDeletedMemberAndBothRawCopies() {
		optionalTables();
		UUID id = evidence(peer, owner);
		UUID history = history(owner, NOW.minusSeconds(10), null);
		var cleanup = new MemberWithdrawalCleanup(jdbc, mock(SettingsChangeCoordinator.class),
			mock(MemberWithdrawalDeletionInvalidator.class), retirement);
		transactions.executeWithoutResult(status -> {
			cleanup.cleanupUnderLocks(owner, NOW);
			status.setRollbackOnly();
		});
		assertThat(jdbc.queryForObject("select count(*) from users where id=?", Long.class, owner)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select retired from keyring_peer_encounter where receipt_id=?",
			Boolean.class, id)).isFalse();
		assertThat(end(history)).isNull();
	}

	private void optionalTables() {
		jdbc.execute("create table keyring_peer_encounter(receipt_id uuid primary key,owner_user_id bigint,"
			+ "peer_user_id bigint,retired boolean not null default false,raw_payload bytea,envelope_hash text,"
			+ "receipt_json text,peer_credential bytea)");
		jdbc.execute("create table keyring_peer_ownership(keyring_id bigint,owner_generation uuid primary key,"
			+ "owner_user_id bigint,valid_from timestamptz not null,valid_until timestamptz)");
	}

	private long member() {
		return jdbc.queryForObject("insert into users(provider,provider_user_id,status,created_at) "
			+ "values ('GOOGLE',?,'ACTIVE',?) returning id", Long.class, UUID.randomUUID().toString(),
			Timestamp.from(NOW));
	}

	private UUID evidence(long actor, long counterpart) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into keyring_peer_encounter(receipt_id,owner_user_id,peer_user_id,raw_payload,"
			+ "envelope_hash,receipt_json,peer_credential) values (?,?,?,?,'original-hash','signed-receipt',?)",
			id, actor, counterpart, new byte[] {1}, new byte[] {2});
		return id;
	}

	private UUID history(long actor, Instant from, Instant until) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into keyring_peer_ownership values (1,?,?,?,?)", id, actor,
			Timestamp.from(from), until == null ? null : Timestamp.from(until));
		return id;
	}

	private Instant end(UUID generation) {
		Timestamp value = jdbc.queryForObject("select valid_until from keyring_peer_ownership "
			+ "where owner_generation=?", Timestamp.class, generation);
		return value == null ? null : value.toInstant();
	}

	private void assertRetired(UUID id) {
		assertThat(jdbc.queryForMap("select retired,raw_payload,envelope_hash,receipt_json,peer_credential "
			+ "from keyring_peer_encounter where receipt_id=?", id)).containsEntry("retired", true)
			.containsEntry("raw_payload", null).containsEntry("envelope_hash", null)
			.containsEntry("receipt_json", null).containsEntry("peer_credential", null);
	}
}
