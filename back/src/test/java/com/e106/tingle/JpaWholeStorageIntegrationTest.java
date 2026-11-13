package com.e106.tingle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 빈 PostgreSQL에 SQL 선행 생성 없이 앱의 모든 엔티티로 만든 실제 저장 구조를 검사한다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class JpaWholeStorageIntegrationTest {
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JsonMapper mapper;

	@Test
	@DisplayName("실제 전체 앱의 JPA 생성은 nullable 기기 epoch를 포함한 설계 35표·298칼럼과 일치한다")
	void generatesExactlyCanonicalTablesAndColumnsWithoutBootstrapSql() throws Exception {
		JsonNode design = mapper.readTree(new ClassPathResource("database/tables.json")
			.getContentAsString(StandardCharsets.UTF_8));
		assertThat(design.path("total_tables").asInt()).isEqualTo(35);
		assertThat(design.path("total_columns").asInt()).isEqualTo(298);
		List<String> expected = new ArrayList<>();
		for (JsonNode table : design.path("tables")) {
			for (JsonNode column : table.path("columns")) {
				String type = column.path("type").asString();
				String sqlType = Map.of("bigint", "int8", "integer", "int4", "boolean", "bool")
					.getOrDefault(type, type);
				if (type.startsWith("varchar(")) {
					sqlType = "varchar:" + type.substring(8, type.length() - 1);
				}
				expected.add(table.path("name").asString() + "." + column.path("name").asString()
					+ ":" + sqlType + ":" + (column.path("nullable").asBoolean() ? "YES" : "NO"));
			}
		}
		List<String> actual = jdbc.query("select table_name,column_name,udt_name,character_maximum_length,"
			+ "is_nullable from information_schema.columns where table_schema='public'", (rs, row) -> {
				String type = rs.getString(3);
				if ("varchar".equals(type)) {
					type += ":" + rs.getInt(4);
				}
				return rs.getString(1) + "." + rs.getString(2) + ":" + type + ":" + rs.getString(5);
			});
		assertThat(actual).containsExactlyInAnyOrderElementsOf(expected).hasSize(298)
			.contains("chat_message.push_state:jsonb:NO", "keyring_sync_run.server_proofs:jsonb:YES",
				"keyring.ownership_epoch:int8:YES", "keyring_sync_run.ownership_epoch:int8:YES");
		List<Map<String, Object>> epochs = jdbc.queryForList("select table_name,data_type,is_nullable,column_default"
			+ " from information_schema.columns where table_schema='public' and column_name='ownership_epoch'");
		assertThat(epochs).hasSize(2).allSatisfy(column -> assertThat(column).containsEntry("data_type", "bigint")
			.containsEntry("is_nullable", "YES").containsEntry("column_default", null));
		assertThat(jdbc.queryForObject("select column_default from information_schema.columns "
			+ "where table_schema='public' and table_name='chat_message' and column_name='push_state'",
			String.class)).isEqualTo("'{}'::jsonb");
		assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
			+ "where table_schema='public' and table_type='BASE TABLE'", Integer.class)).isEqualTo(35);
		assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
			+ "where constraint_schema='public' and constraint_type='FOREIGN KEY'", Integer.class)).isZero();
	}
}
