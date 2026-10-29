package com.e106.tingle.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PushBindingSchemaTest {

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

	private static JdbcTemplate jdbc;

	@BeforeAll
	static void createDisposableDesignSchema() {
		DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(), POSTGRES.getPassword());
		new ResourceDatabasePopulator(new FileSystemResource("database/schema.sql")).execute(source);
		jdbc = new JdbcTemplate(source);
	}

	@Test
	@DisplayName("설계 DDL은 23개 테이블·181개 컬럼과 nullable 연결 세대 두 칸을 만든다")
	void preservesApprovedLayoutAndNullableGenerationTypes() {
		assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
			+ "where table_schema='public' and table_type='BASE TABLE'", Integer.class)).isEqualTo(23);
		assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
			+ "where table_schema='public'", Integer.class)).isEqualTo(181);
		List<Map<String, Object>> columns = jdbc.queryForList("select column_name,data_type,is_nullable,column_default "
			+ "from information_schema.columns where table_schema='public' and table_name='push_installation' "
			+ "and column_name in ('binding_id','linked_at') order by column_name");
		assertThat(columns).hasSize(2);
		assertThat(columns.get(0)).containsEntry("column_name", "binding_id").containsEntry("data_type", "uuid")
			.containsEntry("is_nullable", "YES").containsEntry("column_default", null);
		assertThat(columns.get(1)).containsEntry("column_name", "linked_at")
			.containsEntry("data_type", "timestamp with time zone")
			.containsEntry("is_nullable", "YES").containsEntry("column_default", null);
	}

	@Test
	@DisplayName("연결 증거가 없는 기존 설치에 UUID나 최초 시각을 자동 생성하지 않는다")
	void leavesUnknownLegacyBindingUninvented() {
		UUID installation = UUID.randomUUID();
		jdbc.update("insert into push_installation(client_installation_id,platform,token_ciphertext,updated_at) "
			+ "values (?,'ANDROID','synthetic-ciphertext',?)", installation,
			Timestamp.from(Instant.parse("2026-10-28T00:00:00Z")));
		Map<String, Object> row = jdbc.queryForMap("select user_id,login_family_id,binding_id,linked_at "
			+ "from push_installation where client_installation_id=?", installation);
		assertThat(row).containsEntry("user_id", null).containsEntry("login_family_id", null)
			.containsEntry("binding_id", null).containsEntry("linked_at", null);
	}

	@Test
	@DisplayName("서버가 확인한 연결 ID와 최초 시각을 별도 필드로 손실 없이 보존한다")
	void storesConfirmedBindingAndFirstLinkTime() {
		UUID installation = UUID.randomUUID();
		UUID family = UUID.randomUUID();
		UUID binding = UUID.randomUUID();
		Instant firstLink = Instant.parse("2026-10-28T00:00:00Z");
		jdbc.update("insert into push_installation(client_installation_id,user_id,login_family_id,"
			+ "binding_id,linked_at,platform,token_ciphertext,updated_at) "
			+ "values (?,41,?,?,?,'ANDROID','synthetic-ciphertext',?)", installation, family, binding,
			Timestamp.from(firstLink), Timestamp.from(firstLink.plusSeconds(60)));
		List<Object> values = jdbc.queryForObject("select binding_id,linked_at from push_installation "
			+ "where client_installation_id=?", (rs, row) -> List.of(rs.getObject("binding_id", UUID.class),
				rs.getTimestamp("linked_at").toInstant()), installation);
		assertThat(values).containsExactly(binding, firstLink);
	}
}
