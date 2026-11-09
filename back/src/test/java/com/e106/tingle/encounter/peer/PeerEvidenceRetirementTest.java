package com.e106.tingle.encounter.peer;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.e106.tingle.encounter.ReligionEvidenceRetirement;

import tools.jackson.databind.json.JsonMapper;

/** Disposable PostgreSQL only. Disabling intake must not disable withdrawal of previously stored copies. */
@Testcontainers
class PeerEvidenceRetirementTest {

	private static final Instant TIME = Instant.parse("2026-11-06T00:00:00Z");
	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
	private static DriverManagerDataSource dataSource;
	private static JdbcTemplate jdbc;

	@BeforeAll
	static void isolatedMinimumDomainTables() {
		dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("create table users(id bigint primary key)");
		jdbc.execute("create table settings_snapshot(user_id bigint,settings_version bigint,redaction_reason text)");
	}

	@BeforeEach
	void resetOnlyThisDisposableDatabase() {
		jdbc.execute("drop table if exists keyring_peer_encounter");
		jdbc.execute("truncate users,settings_snapshot");
		jdbc.update("insert into users(id) values (1),(2),(3)");
	}

	@Test
	void disabledIntakeStillRetiresOwnerAndPeerCopiesBeforeEmptySnapshotVersionReturn() {
		new ResourceDatabasePopulator(new ClassPathResource("database/keyring-peer-encounter-270.sql"))
			.execute(dataSource);
		UUID own = row(1, 2);
		UUID peer = row(2, 1);
		UUID unrelated = row(2, 3);
		context().run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(PeerEvidenceRetirement.class);
			assertThat(context.getEnvironment().getProperty("app.keyring.peer.enabled")).isEqualTo("false");
			var religion = new ReligionEvidenceRetirement(jdbc, JsonMapper.builder().build(),
				context.getBeanProvider(PeerEvidenceRetirement.class));
			var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
			transaction.executeWithoutResult(status -> religion.retire(1));
			transaction.executeWithoutResult(status -> religion.retire(1));
		});
		for (UUID receipt : new UUID[] {own, peer}) {
			assertThat(jdbc.queryForObject("select retired from keyring_peer_encounter where receipt_id=?",
				Boolean.class, receipt)).isTrue();
			assertThat(jdbc.queryForMap("select raw_payload,envelope_hash,peer_credential,receipt_json "
				+ "from keyring_peer_encounter where receipt_id=?", receipt).values()).containsOnlyNulls();
			assertThat(jdbc.queryForMap("select event_id,submission_id from keyring_peer_encounter "
				+ "where receipt_id=?", receipt).values()).doesNotContainNull();
		}
		assertThat(jdbc.queryForObject("select retired from keyring_peer_encounter where receipt_id=?",
			Boolean.class, unrelated)).isFalse();
		assertThat(jdbc.queryForObject("select raw_payload from keyring_peer_encounter where receipt_id=?",
			byte[].class, unrelated)).containsExactly((byte)1);
	}

	@Test
	void defaultOffWithNoOptionalTableStartsAndWithdrawalIsANoop() {
		context().run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(PeerEvidenceRetirement.class);
			var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
			transaction.executeWithoutResult(status -> context.getBean(PeerEvidenceRetirement.class).retire(1));
		});
		assertThat(jdbc.queryForObject("select to_regclass('keyring_peer_encounter') is null", Boolean.class)).isTrue();
	}

	private static ApplicationContextRunner context() {
		return new ApplicationContextRunner().withUserConfiguration(PeerEvidenceRetirement.class)
			.withBean(JdbcTemplate.class, () -> jdbc).withPropertyValues("app.keyring.peer.enabled=false",
				"app.keyring.peer.encounters-enabled=false");
	}

	private static UUID row(long owner, long peer) {
		UUID receipt = UUID.randomUUID();
		jdbc.update("insert into keyring_peer_encounter(receipt_id,owner_user_id,peer_user_id,device_id,"
			+ "owner_generation,ownership_epoch,event_id,submission_id,received_at,occurred_at,expires_at,"
			+ "encounter_status,raw_payload,envelope_hash,peer_credential,receipt_json) "
			+ "values (?,?,?,?,?,1,?,?,?,?,?,'VERIFIED_BOTH',?,?,?,?)", receipt, owner, peer, owner,
			UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(TIME), Timestamp.from(TIME),
			Timestamp.from(TIME.plusSeconds(86400)), new byte[] {1}, "a".repeat(64), new byte[] {2}, "{}");
		return receipt;
	}
}
