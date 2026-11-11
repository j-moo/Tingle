package com.e106.tingle.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.e106.tingle.SharedPostgres;

class PushLegacyMigrationTest {

	// 0255529 canonical DDL의 해당 테이블. diagram import의 identity 없는 정의와 구분한다.
	private static final String LEGACY_DDL = """
		CREATE TABLE push_installations (
			installation_id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
			client_installation_id uuid NOT NULL,
			user_id bigint NOT NULL,
			platform text NOT NULL,
			token_ciphertext text NOT NULL,
			login_family_id uuid,
			binding_id uuid,
			linked_at timestamptz,
			updated_at timestamptz NOT NULL,
			CONSTRAINT pk_push_installations PRIMARY KEY (installation_id),
			CONSTRAINT uq_push_installations_1 UNIQUE (client_installation_id),
			CONSTRAINT ck_push_installations_1 CHECK (platform IN ('ANDROID', 'IOS'))
		)
		""";
	private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
	private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

	private static final JdbcConnectionDetails POSTGRES = SharedPostgres.newDatabase();

	@Test
	@DisplayName("과거 테이블 이름 변경은 ID·identity·설치 UUID·암호문 AAD·연결 세대·미확인 NULL을 보존한다")
	void preservesExistingRowsAndIdentityDuringDryRun() throws Exception {
		DriverManagerDataSource source = legacySchema();
		JdbcTemplate jdbc = new JdbcTemplate(source);
		UUID id = UUID.randomUUID();
		UUID family = UUID.randomUUID();
		UUID binding = UUID.randomUUID();
		PushTokenCipher cipher = new PushTokenCipher(KEY);
		String encrypted = cipher.encrypt(id, 41, "synthetic-existing-token");
		jdbc.update("insert into push_installations(client_installation_id,user_id,platform,token_ciphertext,"
			+ "login_family_id,binding_id,linked_at,updated_at) values (?,41,'ANDROID',?,?,?,?,?)",
			id, encrypted, family, binding, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(1)));
		UUID unknownId = UUID.randomUUID();
		jdbc.update("insert into push_installations(client_installation_id,user_id,platform,token_ciphertext,"
			+ "updated_at) values (?,42,'IOS','synthetic-unknown',?)", unknownId, Timestamp.from(NOW));
		Map<String, Object> before = jdbc.queryForMap("select * from push_installations "
			+ "where client_installation_id=?", id);
		migrate(source);
		Map<String, Object> after = jdbc.queryForMap("select * from push_installation "
			+ "where client_installation_id=?", id);
		assertThat(after).containsEntry("id", before.get("installation_id")).containsEntry("user_id", 41L)
			.containsEntry("client_installation_id", id).containsEntry("token_ciphertext", encrypted)
			.containsEntry("login_family_id", family).containsEntry("binding_id", binding)
			.containsEntry("linked_at", before.get("linked_at")).containsEntry("updated_at", before.get("updated_at"));
		assertThat(cipher.decrypt(id, 41, (String)after.get("token_ciphertext")))
			.isEqualTo("synthetic-existing-token");
		assertThat(jdbc.queryForMap("select binding_id,linked_at from push_installation "
			+ "where client_installation_id=?", unknownId)).containsEntry("binding_id", null)
			.containsEntry("linked_at", null);
		Long next = jdbc.queryForObject("insert into push_installation(client_installation_id,platform,"
			+ "token_ciphertext,updated_at) values (?,'ANDROID','synthetic-new',?) returning id",
			Long.class, UUID.randomUUID(), Timestamp.from(NOW));
		assertThat(next).isEqualTo(3L);
		assertThat(jdbc.queryForObject("select identity_generation from information_schema.columns "
			+ "where table_schema=current_schema() and table_name='push_installation' and column_name='id'",
			String.class)).isEqualTo("ALWAYS");
		assertThatThrownBy(() -> jdbc.update("insert into push_installation(client_installation_id,platform,"
			+ "token_ciphertext,updated_at) values (?,'ANDROID','duplicate',?)", id, Timestamp.from(NOW)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("source와 target이 함께 있으면 어느 쪽도 자동 합치거나 덮어쓰지 않는다")
	void refusesAmbiguousTwoTableState() throws Exception {
		DriverManagerDataSource source = legacySchema();
		JdbcTemplate jdbc = new JdbcTemplate(source);
		jdbc.execute("create table push_installation(id bigint)");
		assertThatThrownBy(() -> migrate(source)).isInstanceOf(SQLException.class)
			.hasMessageContaining("Expected only legacy");
		assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
			+ "where table_schema=current_schema() and table_name in ('push_installations','push_installation')",
			Integer.class)).isEqualTo(2);
	}

	@Test
	@DisplayName("반쪽 연결 증거를 현재 시각이나 새 UUID로 자동 수선하지 않는다")
	void refusesPartialGenerationWithoutModifyingSource() throws Exception {
		DriverManagerDataSource source = legacySchema();
		JdbcTemplate jdbc = new JdbcTemplate(source);
		UUID id = UUID.randomUUID();
		jdbc.update("insert into push_installations(client_installation_id,user_id,platform,token_ciphertext,"
			+ "linked_at,updated_at) values (?,41,'ANDROID','synthetic-partial',?,?)",
			id, Timestamp.from(NOW), Timestamp.from(NOW));
		assertThatThrownBy(() -> migrate(source)).isInstanceOf(SQLException.class)
			.hasMessageContaining("Partial connection-generation");
		assertThat(jdbc.queryForMap("select binding_id,linked_at from push_installations "
			+ "where client_installation_id=?", id)).containsEntry("binding_id", null)
			.containsEntry("linked_at", Timestamp.from(NOW));
	}

	private DriverManagerDataSource legacySchema() {
		String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
		DriverManagerDataSource admin = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(), POSTGRES.getPassword());
		new JdbcTemplate(admin).execute("create schema " + schema);
		String base = POSTGRES.getJdbcUrl();
		DriverManagerDataSource source = new DriverManagerDataSource(base + (base.contains("?") ? "&" : "?")
			+ "currentSchema=" + schema, POSTGRES.getUsername(), POSTGRES.getPassword());
		new JdbcTemplate(source).execute(LEGACY_DDL);
		return source;
	}

	private void migrate(DriverManagerDataSource source) throws Exception {
		String sql = Files.readString(Path.of("database/push-installation-176-migration.sql"),
			StandardCharsets.UTF_8);
		try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}
