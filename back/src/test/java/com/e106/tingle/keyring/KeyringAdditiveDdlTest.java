package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;

import com.e106.tingle.ContractFixtures;
import com.e106.tingle.TestcontainersConfiguration;

import tools.jackson.databind.json.JsonMapper;

/** 공유 DB가 아닌 Testcontainers 안의 별도 schema에서 설계 SQL을 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class KeyringAdditiveDdlTest {
	@Autowired
	private DataSource dataSource;

	@Test
	void canonicalDdlMatchesDeclaredTablesAndColumnsWithoutPhysicalReferences() throws Exception {
		var canonical = JsonMapper.builder().build().readTree(new ClassPathResource("database/tables.json")
			.getContentAsString(StandardCharsets.UTF_8));
		inDisposableSchema(jdbc -> {
			jdbc.execute(new ClassPathResource("database/schema.sql")
				.getContentAsString(StandardCharsets.UTF_8));
			assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
				+ "where table_schema=current_schema() and table_type='BASE TABLE'", Integer.class))
				.isEqualTo(canonical.path("total_tables").asInt());
			assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
				+ "where table_schema=current_schema()", Integer.class))
				.isEqualTo(canonical.path("total_columns").asInt());
			assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
				+ "where constraint_schema=current_schema() and constraint_type='FOREIGN KEY'", Integer.class))
				.isZero();
		});
	}

	@Test
	void optionalResetSqlAddsOnlyItsSeventeenColumnTableBeyondTheJpaBaseline() throws Exception {
		var canonical = JsonMapper.builder().build().readTree(new ClassPathResource("database/tables.json")
			.getContentAsString(StandardCharsets.UTF_8));
		inDisposableSchema(jdbc -> {
			jdbc.execute(new ClassPathResource("database/schema.sql").getContentAsString(StandardCharsets.UTF_8));
			jdbc.execute(new ClassPathResource("database/keyring-reset-249.sql")
				.getContentAsString(StandardCharsets.UTF_8));
			assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
				+ "where table_schema=current_schema() and table_type='BASE TABLE'", Integer.class))
				.isEqualTo(canonical.path("total_tables").asInt() + 1);
			assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
				+ "where table_schema=current_schema()", Integer.class))
				.isEqualTo(canonical.path("total_columns").asInt() + 17);
			assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
				+ "where table_schema=current_schema() and table_name='keyring_reset_run'", Integer.class))
				.isEqualTo(17);
			assertThat(jdbc.queryForList("select contype::text from pg_constraint where conrelid="
				+ "'keyring_reset_run'::regclass", String.class)).containsExactlyInAnyOrder("p", "u", "u", "c");
			assertThat(jdbc.queryForObject("select count(*) from keyring_reset_run", Long.class)).isZero();
		});
	}

	@Test
	void epochAdditionsPreserveLegacyOwnershipWithoutGuessingDeviceCounters() throws Exception {
		inDisposableSchema(jdbc -> {
			jdbc.execute(new ClassPathResource("database/schema.sql").getContentAsString(StandardCharsets.UTF_8));
			jdbc.execute("alter table keyring drop column ownership_epoch");
			jdbc.execute("alter table keyring_sync_run drop column ownership_epoch");
			jdbc.update("insert into keyring(serial_number,firmware_version,owner_user_id,state,owner_generation,"
				+ "synced_settings_version) values ('epoch-legacy','old',91,'REGISTERED',?,7)", UUID.randomUUID());
			var before = jdbc.queryForMap("select * from keyring");
			Long identity = jdbc.queryForObject("select 'keyring'::regclass::oid::bigint", Long.class);
			String ddl = new ClassPathResource("database/keyring-ownership-epoch-249.sql")
				.getContentAsString(StandardCharsets.UTF_8);
			jdbc.execute(ddl);
			jdbc.execute(ddl);
			var after = jdbc.queryForMap("select * from keyring");
			assertThat(after.remove("ownership_epoch")).isNull();
			assertThat(after).isEqualTo(before);
			assertThat(jdbc.queryForObject("select 'keyring'::regclass::oid::bigint", Long.class)).isEqualTo(identity);
			assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
				+ "where table_schema=current_schema() and column_name='ownership_epoch' and is_nullable='YES'",
				Integer.class)).isEqualTo(2);
		});
	}

	@Test
	void additiveDdlRetainsExistingDeviceRowsTableIdentityAndUnrelatedEvidence() throws Exception {
		inDisposableSchema(jdbc -> {
			jdbc.execute("create type keyring_state as enum ('UNBOUND','REGISTERED')");
			jdbc.execute("create table keyring(id bigint generated always as identity primary key,"
				+ "serial_number varchar(64) not null unique,firmware_version varchar(30) not null,"
				+ "owner_user_id bigint,state keyring_state not null,synced_settings_version bigint,"
				+ "synced_at timestamptz)");
			jdbc.execute("create table unrelated_evidence(id integer primary key,note text not null)");
			jdbc.update("insert into unrelated_evidence values (1,'keep exact evidence')");
			jdbc.update("insert into keyring(serial_number,firmware_version,owner_user_id,state,"
				+ "synced_settings_version,synced_at) values ('known-serial','old-firmware',91,'REGISTERED',"
				+ "7,'2026-10-28T00:00:00Z')");
			Map<String, Object> device = originalDevice(jdbc);
			Map<String, Object> identifiers = identities(jdbc);
			jdbc.execute(new ClassPathResource("database/keyring-proof-176-additions.sql")
				.getContentAsString(StandardCharsets.UTF_8));
			assertThat(originalDevice(jdbc)).isEqualTo(device);
			assertThat(identities(jdbc)).isEqualTo(identifiers);
			assertThat(jdbc.queryForObject("select note from unrelated_evidence", String.class))
				.isEqualTo("keep exact evidence");
			assertThat(jdbc.queryForObject("select count(*) from keyring where credential_key_id is not null "
				+ "or credential_public_key is not null or credential_revoked_at is not null "
				+ "or owner_generation is not null", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
				+ "where table_schema=current_schema() and table_name in "
				+ "('registration_challenge','keyring_sync_run')", Integer.class)).isEqualTo(38);
		});
	}

	@Test
	void registrationAdditionsPreserveLegacyRowsAndLeaveUnknownReceiptsNull() throws Exception {
		inDisposableSchema(jdbc -> {
			jdbc.execute(new ClassPathResource("database/schema.sql")
				.getContentAsString(StandardCharsets.UTF_8));
			jdbc.execute("alter table registration_challenge drop column registration_request_key,"
				+ "drop column registration_request_hash,drop column registered_firmware_version,"
				+ "drop column registered_status");
			for (int index = 0; index < 2; index++) {
				jdbc.update("insert into registration_challenge(challenge_id,user_id,request_key,nonce,"
					+ "protocol_version,issued_at,expires_at,revision) values (?,91,?,?,'tingle-device-proof-v1',"
					+ "'2026-10-28T00:00:00Z','2026-10-28T00:05:00Z',0)",
					UUID.randomUUID(), UUID.randomUUID(), "A".repeat(43));
			}
			var before = jdbc.queryForList("select * from registration_challenge order by id");
			Long identity = jdbc.queryForObject("select 'registration_challenge'::regclass::oid::bigint",
				Long.class);
			jdbc.execute(new ClassPathResource("database/keyring-registration-100-additions.sql")
				.getContentAsString(StandardCharsets.UTF_8));
			var after = jdbc.queryForList("select * from registration_challenge order by id");
			for (var row : after) {
				assertThat(row.remove("registration_request_key")).isNull();
				assertThat(row.remove("registration_request_hash")).isNull();
				assertThat(row.remove("registered_firmware_version")).isNull();
				assertThat(row.remove("registered_status")).isNull();
			}
			assertThat(after).isEqualTo(before);
			assertThat(jdbc.queryForObject("select 'registration_challenge'::regclass::oid::bigint", Long.class))
				.isEqualTo(identity);
			assertThat(jdbc.queryForObject("select indisunique from pg_index where indexrelid="
				+ "'uq_registration_challenge_registration_request'::regclass", Boolean.class)).isTrue();
		});
	}

	@Test
	void syncAdditionsPreservePayloadAndOriginalRequestsWithoutInferringFormatsOrAliases() throws Exception {
		inDisposableSchema(jdbc -> {
			jdbc.execute(new ClassPathResource("database/schema.sql")
				.getContentAsString(StandardCharsets.UTF_8));
			jdbc.execute("alter table keyring_sync_run drop column payload_format,drop column request_key_aliases");
			jdbc.update("insert into keyring_sync_run(sync_run_id,keyring_id,owner_user_id,owner_generation,"
				+ "credential_key_id,protocol_version,settings_version,payload,payload_hash,nonce,created_at,"
				+ "state,request_key,revision) values (?,1,91,?,'legacy-key','tingle-device-proof-v1',7,?,? ,?,"
				+ "'2026-10-28T00:00:00Z','PENDING',?,0)", UUID.randomUUID(), UUID.randomUUID(),
				new byte[] {0, 1, -1}, "a".repeat(64), "A".repeat(43), UUID.randomUUID());
			var before = jdbc.queryForMap("select * from keyring_sync_run");
			Long identity = jdbc.queryForObject("select 'keyring_sync_run'::regclass::oid::bigint", Long.class);
			jdbc.execute(new ClassPathResource("database/keyring-sync-68-additions.sql")
				.getContentAsString(StandardCharsets.UTF_8));
			var after = jdbc.queryForMap("select * from keyring_sync_run");
			assertThat(after.remove("payload_format")).isNull();
			assertThat(after.remove("request_key_aliases")).isNull();
			assertThat((byte[])after.remove("payload")).containsExactly((byte[])before.remove("payload"));
			assertThat(after).isEqualTo(before);
			assertThat(jdbc.queryForObject("select 'keyring_sync_run'::regclass::oid::bigint", Long.class))
				.isEqualTo(identity);
		});
	}

	@Test
	void serverProofColumnPreservesLegacyRunWithoutInventingSignedEvidence() throws Exception {
		inDisposableSchema(jdbc -> {
			jdbc.execute(new ClassPathResource("database/schema.sql")
				.getContentAsString(StandardCharsets.UTF_8));
			jdbc.execute("alter table keyring_sync_run drop column server_proofs");
			jdbc.update("insert into keyring_sync_run(sync_run_id,keyring_id,owner_user_id,owner_generation,"
				+ "credential_key_id,protocol_version,settings_version,payload,payload_hash,nonce,created_at,"
				+ "state,request_key,revision) values (?,1,91,?,'legacy-key','tingle-device-proof-v1',7,?,?,?,"
				+ "'2026-10-28T00:00:00Z','PENDING',?,0)", UUID.randomUUID(), UUID.randomUUID(),
				new byte[] {0, 1, -1}, "a".repeat(64), "A".repeat(43), UUID.randomUUID());
			var before = jdbc.queryForMap("select * from keyring_sync_run");
			jdbc.execute(ContractFixtures.keyringServerProofsSql());
			var after = jdbc.queryForMap("select * from keyring_sync_run");
			assertThat(after.remove("server_proofs")).isNull();
			assertThat((byte[])after.remove("payload")).containsExactly((byte[])before.remove("payload"));
			assertThat(after).isEqualTo(before);
		});
	}

	private Map<String, Object> originalDevice(JdbcTemplate jdbc) {
		return jdbc.queryForMap("select id,serial_number,firmware_version,owner_user_id,state,"
			+ "synced_settings_version,synced_at from keyring");
	}

	private Map<String, Object> identities(JdbcTemplate jdbc) {
		return jdbc.queryForMap("select 'keyring'::regclass::oid::bigint as device,"
			+ "'unrelated_evidence'::regclass::oid::bigint as evidence");
	}

	private void inDisposableSchema(SqlAssertions assertions) throws Exception {
		String schema = "proof176_" + UUID.randomUUID().toString().replace("-", "");
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("create schema " + schema);
			connection.setSchema(schema);
			try {
				assertions.run(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
			} catch (Exception exception) {
				statement.execute("rollback");
				throw exception;
			} finally {
				connection.setSchema("public");
				statement.execute("drop schema " + schema + " cascade");
			}
		}
	}

	@FunctionalInterface
	private interface SqlAssertions {
		void run(JdbcTemplate jdbc) throws Exception;
	}
}
