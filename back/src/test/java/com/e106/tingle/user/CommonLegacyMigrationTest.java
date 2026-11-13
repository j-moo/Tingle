package com.e106.tingle.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.e106.tingle.SharedPostgres;

class CommonLegacyMigrationTest {
	private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
	private static final String PHOTO_BASE = "https://photos.example/";

	private static final JdbcConnectionDetails POSTGRES = SharedPostgres.newDatabase();

	@Test
	@DisplayName("제자리 이관은 ID·증가위치·모든 회원상태·시각·프로필·항목별 동의 이력을 보존한다")
	void preservesLegacyDataWithoutInventingAggregateAgreement() throws Exception {
		DriverManagerDataSource source = legacySchema();
		JdbcTemplate jdbc = new JdbcTemplate(source);
		jdbc.update("update profiles set image_url='https://photos.example/profile/1/photo.webp',completed_at=? "
			+ "where user_id=1", Timestamp.from(NOW.minusSeconds(20)));
		for (String status : List.of("SUSPENDED", "WITHDRAWAL_PENDING", "WITHDRAWN")) {
			jdbc.update("insert into app_users(provider,provider_subject,status,created_at,withdrawal_due_at,"
				+ "withdrawn_at) values ('NAVER',?,?,?, ?,?)", status, status, Timestamp.from(NOW),
				"WITHDRAWAL_PENDING".equals(status) ? Timestamp.from(NOW.plusSeconds(7200)) : null,
				"WITHDRAWN".equals(status) ? Timestamp.from(NOW.plusSeconds(60)) : null);
		}
		List<Map<String, Object>> accounts = jdbc.queryForList("select * from app_users order by user_id");
		Map<String, Object> profile = jdbc.queryForMap("select * from profiles where user_id=1");
		List<Map<String, Object>> consents = jdbc.queryForList("select * from user_consents order by consent_id");
		jdbc.execute("select setval(pg_get_serial_sequence('app_users','user_id'),100)");
		jdbc.execute("select setval(pg_get_serial_sequence('user_consents','consent_id'),200)");
		migrate(source, PHOTO_BASE);
		for (Map<String, Object> old : accounts) {
			Map<String, Object> saved = jdbc.queryForMap("select * from users where id=?", old.get("user_id"));
			assertThat(saved).containsEntry("id", old.get("user_id"))
				.containsEntry("provider_user_id", old.get("provider_subject"))
				.containsEntry("status", old.get("status")).containsEntry("created_at", old.get("created_at"))
				.containsEntry("signup_completed_at", old.get("signup_completed_at"))
				.containsEntry("withdrawal_due_at", old.get("withdrawal_due_at"))
				.containsEntry("withdrawn_at", old.get("withdrawn_at"))
				.containsEntry("agreed_at", null).containsEntry("is_operator", false);
			assertThat(saved.get("provider").toString()).isEqualTo(old.get("provider"));
		}
		Map<String, Object> savedProfile = jdbc.queryForMap("select * from profile where user_id=1");
		for (String column : List.of("user_id", "nickname", "nickname_key", "birth_date", "introduction",
			"completed_at")) {
			assertThat(savedProfile).containsEntry(column, profile.get(column));
		}
		assertThat(savedProfile).containsEntry("photo_url", profile.get("image_url"))
			.containsEntry("active_personality_id", null);
		for (String column : List.of("gender", "smoking_status", "drinking_frequency", "religion")) {
			assertThat(savedProfile.get(column).toString()).isEqualTo(profile.get(column));
		}
		assertThat(jdbc.queryForList("select * from user_consents order by consent_id")).isEqualTo(consents);
		assertThat(jdbc.queryForObject("insert into users(provider,provider_user_id,status,created_at) "
			+ "values ('GOOGLE','new-account','ACTIVE',?) returning id", Long.class, Timestamp.from(NOW)))
			.isEqualTo(101L);
		assertThat(jdbc.queryForObject("insert into user_consents(user_id,consent_type,document_version,agreed,"
			+ "recorded_at) values (1,'TERMS','new',true,?) returning consent_id", Long.class, Timestamp.from(NOW)))
			.isEqualTo(201L);
		jdbc.update("insert into profile(user_id,nickname) values (101,'NewNickname')");
		assertThatThrownBy(() -> jdbc.update("insert into profile(user_id,nickname) values (102,'original')"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("옛 표와 새 표가 공존하면 어느 쪽도 덮어쓰거나 자동 합치지 않는다")
	void refusesEitherExistingTargetTable() throws Exception {
		for (String target : List.of("users", "profile")) {
			DriverManagerDataSource source = legacySchema();
			JdbcTemplate jdbc = new JdbcTemplate(source);
			jdbc.execute("create table " + target + "(sentinel text)");
			jdbc.update("insert into " + target + " values ('preserve-target')");
			assertThatThrownBy(() -> migrate(source, PHOTO_BASE)).isInstanceOf(SQLException.class)
				.hasMessageContaining("Expected only known legacy");
			assertThat(jdbc.queryForObject("select sentinel from " + target, String.class))
				.isEqualTo("preserve-target");
			assertSourceUnchanged(jdbc);
		}
	}

	@Test
	@DisplayName("길이·정체성·닉네임·enum·사진 불일치는 잘라내거나 대체하지 않고 원본에서 중단한다")
	void refusesIncompatibleValuesBeforeAnyRename() throws Exception {
		List<Consumer<JdbcTemplate>> invalidChanges = List.of(
			jdbc -> jdbc.update("update app_users set provider='UNKNOWN' where user_id=1"),
			jdbc -> jdbc.update("update app_users set provider_subject=? where user_id=1", "x".repeat(256)),
			jdbc -> jdbc.update("update app_users set email=? where user_id=1", "x".repeat(321)),
			jdbc -> jdbc.update("update app_users set status='WITHDRAWN',provider_subject=null where user_id=1"),
			jdbc -> jdbc.update("update profiles set nickname=? where user_id=1", "x".repeat(21)),
			jdbc -> jdbc.update("update profiles set gender='UNKNOWN' where user_id=1"),
			jdbc -> jdbc.update("insert into profiles(user_id,nickname,nickname_key) values (2,'ORIGINAL','other')"),
			jdbc -> jdbc.update("update profiles set image_url='https://photos.example/profile/2/other.webp'"),
			jdbc -> jdbc.update("update profiles set image_url='https://photos.example/profile/1/file?signature=x'"));
		for (Consumer<JdbcTemplate> change : invalidChanges) {
			DriverManagerDataSource source = legacySchema();
			JdbcTemplate jdbc = new JdbcTemplate(source);
			change.accept(jdbc);
			List<Map<String, Object>> usersBefore = jdbc.queryForList("select * from app_users order by user_id");
			List<Map<String, Object>> profilesBefore = jdbc.queryForList("select * from profiles order by user_id");
			assertThatThrownBy(() -> migrate(source, PHOTO_BASE)).isInstanceOf(SQLException.class);
			assertThat(jdbc.queryForList("select * from app_users order by user_id")).isEqualTo(usersBefore);
			assertThat(jdbc.queryForList("select * from profiles order by user_id")).isEqualTo(profilesBefore);
			assertSourceUnchanged(jdbc);
		}
	}

	@Test
	@DisplayName("기존 사진은 확인된 서버 저장소 설정 없이는 일괄 승인하지 않는다")
	void requiresVerifiedPhotoStoragePrefix() throws Exception {
		DriverManagerDataSource source = legacySchema();
		JdbcTemplate jdbc = new JdbcTemplate(source);
		jdbc.update("update profiles set image_url='https://photos.example/profile/1/photo.webp'");
		assertThatThrownBy(() -> migrate(source, null)).isInstanceOf(SQLException.class)
			.hasMessageContaining("verified server storage prefix");
		assertSourceUnchanged(jdbc);
	}

	@Test
	@DisplayName("기존 명명 enum과 충돌해 형변환이 실패해도 앞선 테이블 변경까지 전부 롤백한다")
	void rollsBackEarlierDdlOnLateEnumConversionFailure() throws Exception {
		DriverManagerDataSource source = legacySchema();
		JdbcTemplate jdbc = new JdbcTemplate(source);
		jdbc.execute("create type gender as enum ('INCOMPATIBLE')");
		assertThatThrownBy(() -> migrate(source, PHOTO_BASE)).isInstanceOf(SQLException.class);
		assertSourceUnchanged(jdbc);
		assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
			+ "where table_schema=current_schema() and table_name in ('users','profile')", Integer.class)).isZero();
	}

	private void assertSourceUnchanged(JdbcTemplate jdbc) {
		assertThat(jdbc.queryForObject("select count(*) from app_users", Integer.class)).isPositive();
		assertThat(jdbc.queryForObject("select nickname from profiles where user_id=1", String.class))
			.isNotNull();
		assertThat(jdbc.queryForObject("select count(*) from user_consents", Integer.class)).isEqualTo(3);
	}

	private DriverManagerDataSource legacySchema() {
		String schema = "common_migration_" + UUID.randomUUID().toString().replace("-", "");
		DriverManagerDataSource admin = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(), POSTGRES.getPassword());
		new JdbcTemplate(admin).execute("create schema " + schema);
		String base = POSTGRES.getJdbcUrl();
		DriverManagerDataSource source = new DriverManagerDataSource(base + (base.contains("?") ? "&" : "?")
			+ "currentSchema=" + schema, POSTGRES.getUsername(), POSTGRES.getPassword());
		new ResourceDatabasePopulator(new ClassPathResource("common-user-profile-151-legacy.sql")).execute(source);
		JdbcTemplate jdbc = new JdbcTemplate(source);
		jdbc.update("insert into app_users(provider,provider_subject,status,created_at,signup_completed_at,email) "
			+ "values ('KAKAO','legacy-account','ACTIVE',?,?, 'private@example.test')",
			Timestamp.from(NOW.minusSeconds(3600)), Timestamp.from(NOW.minusSeconds(30)));
		jdbc.update("insert into profiles(user_id,nickname,nickname_key,birth_date,gender,smoking_status,"
			+ "drinking_frequency,religion,introduction) "
			+ "values (1,'Original','original','1995-01-02','MALE','NON_SMOKER','NEVER','UNDISCLOSED','unchanged')");
		for (boolean agreed : List.of(true, false, true)) {
			jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at,locale,"
				+ "consent_screen) values (1,'AI_RISK_DETECTION','legacy-version',?,?,'ko-KR','first-screen')",
				agreed, Timestamp.from(NOW));
		}
		return source;
	}

	private void migrate(DriverManagerDataSource source, String photoBase) throws Exception {
		String sql = Files.readString(Path.of("database/users-profile-151-migration.sql"),
			StandardCharsets.UTF_8);
		try (var connection = source.getConnection(); var statement = connection.createStatement()) {
			if (photoBase != null) {
				String configure = "select set_config('tingle.profile_photo_base_url',?,false)";
				try (var setting = connection.prepareStatement(configure)) {
					setting.setString(1, photoBase);
					setting.execute();
				}
			}
			statement.execute(sql);
		}
	}
}
