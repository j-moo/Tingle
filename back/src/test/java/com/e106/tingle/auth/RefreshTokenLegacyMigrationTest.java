package com.e106.tingle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class RefreshTokenLegacyMigrationTest {

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

	@Test
	void preservesHashIdentityAndLifecycleWithoutGuessingMetadata() throws Exception {
		DriverManagerDataSource source = legacySchema();
		JdbcTemplate jdbc = new JdbcTemplate(source);
		UUID family = UUID.randomUUID();
		byte[] hash = new byte[32];
		hash[0] = 41;
		Instant now = Instant.parse("2026-10-28T00:00:00Z");
		jdbc.update("insert into refresh_tokens(user_id,family_id,token_hash,expires_at,used_at,revoked_at) "
			+ "values (41,?,?,?,?,?)", family, hash, Timestamp.from(now.plusSeconds(3600)),
			Timestamp.from(now), Timestamp.from(now.plusSeconds(1)));
		Map<String, Object> before = jdbc.queryForMap("select * from refresh_tokens");
		// Advance the sequence beyond MAX without inserting a row: rename must preserve its high-water value.
		jdbc.execute("select setval(pg_get_serial_sequence('refresh_tokens','token_id'),100)");
		migrate(source);
		Map<String, Object> after = jdbc.queryForMap("select * from refresh_token");
		assertThat(after).containsEntry("id", before.get("token_id")).containsEntry("user_id", 41L)
			.containsEntry("family_id", family).containsEntry("expires_at", before.get("expires_at"))
			.containsEntry("used_at", before.get("used_at")).containsEntry("revoked_at", before.get("revoked_at"))
			.containsEntry("device_name", null).containsEntry("last_used_at", null);
		assertThat((byte[])after.get("token_hash")).containsExactly(hash);
		hash[0] = 42;
		assertThat(jdbc.queryForObject("insert into refresh_token(user_id,family_id,token_hash,expires_at) "
			+ "values (41,?,?,?) returning id", Long.class, family, hash, Timestamp.from(now))).isEqualTo(101L);
		assertThat(jdbc.queryForObject("select identity_generation from information_schema.columns "
			+ "where table_schema=current_schema() and table_name='refresh_token' and column_name='id'",
			String.class)).isEqualTo("ALWAYS");
	}

	@Test
	void refusesTwoTableStateWithoutOverwritingEither() throws Exception {
		DriverManagerDataSource source = legacySchema();
		JdbcTemplate jdbc = new JdbcTemplate(source);
		jdbc.execute("create table refresh_token(id bigint)");
		assertThatThrownBy(() -> migrate(source)).isInstanceOf(SQLException.class)
			.hasMessageContaining("Expected only legacy");
		assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
			+ "where table_schema=current_schema() and table_name in ('refresh_tokens','refresh_token')",
			Integer.class)).isEqualTo(2);
	}

	private DriverManagerDataSource legacySchema() {
		String schema = "refresh_migration_" + UUID.randomUUID().toString().replace("-", "");
		DriverManagerDataSource admin = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(), POSTGRES.getPassword());
		new JdbcTemplate(admin).execute("create schema " + schema);
		String base = POSTGRES.getJdbcUrl();
		DriverManagerDataSource source = new DriverManagerDataSource(base + (base.contains("?") ? "&" : "?")
			+ "currentSchema=" + schema, POSTGRES.getUsername(), POSTGRES.getPassword());
		new JdbcTemplate(source).execute("""
			CREATE TABLE refresh_tokens (
				token_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL PRIMARY KEY,
				user_id bigint NOT NULL, family_id uuid NOT NULL,
				token_hash bytea NOT NULL UNIQUE CHECK (octet_length(token_hash)=32),
				expires_at timestamptz NOT NULL, used_at timestamptz, revoked_at timestamptz
			)
			""");
		return source;
	}

	private void migrate(DriverManagerDataSource source) throws Exception {
		String sql = Files.readString(Path.of("../docs/database/refresh-token-151-migration.sql"),
			StandardCharsets.UTF_8);
		try (var connection = source.getConnection(); var statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}
