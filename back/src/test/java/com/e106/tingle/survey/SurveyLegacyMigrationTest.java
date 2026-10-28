package com.e106.tingle.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class SurveyLegacyMigrationTest {

	private static final Instant COMPLETED = Instant.parse("2026-10-27T03:04:05Z");
	private static final String VERSION = "SIS-Q-1.1-100";
	private static final String RESPONSE = "{\"answers\":{\"Q001\":4,\"Q002\":\"4\",\"Q003\":false,\"Q004\":null},"
		+ "\"metadata\":{\"ownAge\":25},\"audit\":{\"old\":\"keep\"}}";

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

	private static DriverManagerDataSource source;
	private static JdbcTemplate jdbc;

	@BeforeAll
	static void connectToDisposableDatabase() {
		source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(source);
	}

	@BeforeEach
	void createKnownLegacySchema() {
		// This schema belongs only to the disposable container above.
		jdbc.execute("drop schema public cascade");
		jdbc.execute("create schema public");
		new ResourceDatabasePopulator(new ClassPathResource("survey-175-legacy.sql")).execute(source);
	}

	@Test
	@DisplayName("제자리 이관은 번호·UUID·요청 키·revision·완료시각·JSON scalar와 identity 고수위를 보존한다")
	void preservesConfirmedLegacyDataWithoutInventingMissingEvidence() throws Exception {
		UUID publicId = UUID.randomUUID();
		UUID request = UUID.randomUUID();
		long id = insertLegacy(1, VERSION, RESPONSE, publicId, request, COMPLETED);
		jdbc.queryForObject("select setval(pg_get_serial_sequence('survey_responses','response_id'),5000,true)",
			Long.class);
		migrate();
		Map<String, Object> row = jdbc.queryForMap("select id,legacy_public_id,client_request_id,revision,"
			+ "completed_at,definition_snapshot,jsonb_exists(response_context,'answers') as hidden_answers "
			+ "from survey where id=?", id);
		assertThat(row).containsEntry("id", id).containsEntry("legacy_public_id", publicId)
			.containsEntry("client_request_id", request).containsEntry("revision", 7L)
			.containsEntry("completed_at", Timestamp.from(COMPLETED)).containsEntry("definition_snapshot", null)
			.containsEntry("hidden_answers", false);
		assertThat(jdbc.queryForObject("select response_context = cast(? as jsonb) from survey where id=?",
			Boolean.class, "{\"metadata\":{\"ownAge\":25},\"audit\":{\"old\":\"keep\"}}", id)).isTrue();
		assertThat(jdbc.queryForList("select answer_type from survey_answer where survey_id=? order by question_id",
			String.class, id)).containsExactly("NUMBER", "STRING", "BOOLEAN", "NULL");
		assertThat(jdbc.queryForList("select answer_value from survey_answer where survey_id=? order by question_id",
			String.class, id)).containsExactly("4", "4", "false", "null");
		assertThat(jdbc.queryForObject("select count(*) from survey_answer where survey_id=? and answered_at is null",
			Integer.class, id)).isEqualTo(4);
		assertThat(jdbc.queryForObject("insert into survey(user_id,survey_version) values (2,?) returning id",
			Long.class, VERSION)).isEqualTo(5001L);
		assertThat(jdbc.queryForObject("select legacy_public_id from survey where id=5001", UUID.class)).isNull();
		assertThat(jdbc.queryForObject("select to_regclass('public.survey_responses')::text", String.class)).isNull();
	}

	@Test
	@DisplayName("알 수 없거나 저장 한도를 넘는 과거 버전은 이름 변경 전 거부하고 원본을 보존한다")
	void rejectsUnknownAndOversizedVersionsAtomically() {
		for (String version : List.of("missing-version", "v".repeat(31))) {
			createKnownLegacySchema();
			insertLegacy(1, version, RESPONSE, UUID.randomUUID(), UUID.randomUUID(), null);
			assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
			assertLegacySourceUnchanged(1, RESPONSE);
		}
	}

	@Test
	@DisplayName("복합 답·한도 초과·알 수 없는 문항은 잘라내거나 다른 값으로 바꾸지 않는다")
	void rejectsUnrepresentableAnswersAtomically() {
		for (String response : List.of("{\"answers\":{\"Q001\":[1,2]}}",
			"{\"answers\":{\"Q001\":\"" + "x".repeat(101) + "\"}}", "{\"answers\":{\"Q999\":4}}")) {
			createKnownLegacySchema();
			insertLegacy(1, VERSION, response, UUID.randomUUID(), UUID.randomUUID(), null);
			assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
			assertLegacySourceUnchanged(1, response);
		}
	}

	@Test
	@DisplayName("새 표가 공존하면 기존 번호와 기록을 임의 합치지 않고 이관을 거부한다")
	void rejectsAnExistingTargetTable() {
		insertLegacy(1, VERSION, RESPONSE, UUID.randomUUID(), UUID.randomUUID(), null);
		jdbc.execute("create table survey(id bigint primary key)");
		assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
		assertThat(jdbc.queryForObject("select count(*) from survey_responses", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from survey", Integer.class)).isZero();
	}

	@Test
	@DisplayName("중복 작성 회차는 임의 하나를 고르지 않고 전체 이관을 거부한다")
	void rejectsSeveralOpenDraftsWithoutMergingThem() {
		insertLegacy(1, VERSION, RESPONSE, UUID.randomUUID(), UUID.randomUUID(), null);
		insertLegacy(1, VERSION, RESPONSE, UUID.randomUUID(), UUID.randomUUID(), null);
		assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
		assertLegacySourceUnchanged(2, RESPONSE);
	}

	@Test
	@DisplayName("다른 search_path의 동명 원본을 건드리지 않고 검사한 public 표만 이관한다")
	void migratesOnlyTheGuardedPublicSourceDespiteAnotherSearchPath() throws Exception {
		insertLegacy(1, VERSION, RESPONSE, UUID.randomUUID(), UUID.randomUUID(), null);
		jdbc.execute("create schema shadow");
		jdbc.execute("create table shadow.survey_responses (like public.survey_responses including all)");
		jdbc.update("insert into shadow.survey_responses(user_id,version_code,client_request_id,response_json) "
			+ "values (99,'unrelated-shadow-version',?,'{\"answers\":{}}')", UUID.randomUUID());
		try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("set search_path to shadow,public");
			statement.execute(Files.readString(Path.of("../docs/database/survey-175-migration.sql")));
		}
		assertThat(jdbc.queryForObject("select count(*) from public.survey", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select version_code from shadow.survey_responses", String.class))
			.isEqualTo("unrelated-shadow-version");
		assertThat(jdbc.queryForObject("select to_regclass('shadow.survey')::text", String.class)).isNull();
		jdbc.execute("drop schema shadow cascade");
	}

	@Test
	@DisplayName("동일 물리 연결의 임시 동명 표가 public 원본보다 먼저 선택되지 않도록 고정한다")
	void preservesAnImplicitTemporarySourceOnTheSamePhysicalConnection() throws Exception {
		insertLegacy(1, VERSION, RESPONSE, UUID.randomUUID(), UUID.randomUUID(), null);
		try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("create temp table survey_responses (like public.survey_responses including all)");
			try (PreparedStatement temporary = connection.prepareStatement("insert into pg_temp.survey_responses "
				+ "(user_id,version_code,client_request_id,response_json) values (99,'unrelated-temp-version',?,"
				+ "'{\"answers\":{}}')")) {
				temporary.setObject(1, UUID.randomUUID());
				temporary.executeUpdate();
			}
			statement.execute(Files.readString(Path.of("../docs/database/survey-175-migration.sql")));
			try (var rows = statement.executeQuery("select version_code from pg_temp.survey_responses")) {
				assertThat(rows.next()).isTrue();
				assertThat(rows.getString(1)).isEqualTo("unrelated-temp-version");
			}
			try (var rows = statement.executeQuery("select count(*) from public.survey")) {
				assertThat(rows.next()).isTrue();
				assertThat(rows.getInt(1)).isEqualTo(1);
			}
		}
	}

	private long insertLegacy(long userId, String version, String response, UUID publicId, UUID request,
		Instant completedAt) {
		return jdbc.queryForObject("insert into survey_responses(public_id,user_id,version_code,client_request_id,"
			+ "response_json,revision,completed_at) values (?,?,?,?,cast(? as jsonb),7,?) returning response_id",
			Long.class, publicId, userId, version, request, response,
			completedAt == null ? null : Timestamp.from(completedAt));
	}

	private void migrate() throws Exception {
		String sql = Files.readString(Path.of("../docs/database/survey-175-migration.sql"));
		try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private void assertLegacySourceUnchanged(int rows, String response) {
		assertThat(jdbc.queryForObject("select count(*) from survey_responses", Integer.class)).isEqualTo(rows);
		assertThat(jdbc.queryForObject("select bool_and(response_json=cast(? as jsonb)) from survey_responses",
			Boolean.class, response)).isTrue();
		assertThat(jdbc.queryForObject("select to_regclass('public.survey')::text", String.class)).isNull();
		assertThat(jdbc.queryForObject("select to_regclass('public.survey_answer')::text", String.class)).isNull();
	}
}
