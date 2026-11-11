package com.e106.tingle.global.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.e106.tingle.SharedPostgres;
import com.e106.tingle.chat.ChatSafetyNotice;
import com.e106.tingle.operator.OperatorAudit;
import com.e106.tingle.personality.Personality;
import com.e106.tingle.preference.MatchingPreference;
import com.e106.tingle.report.ChatReport;
import com.e106.tingle.report.ChatReportMessage;
import com.e106.tingle.settings.UserSetting;
import com.e106.tingle.support.ServiceCase;
import com.e106.tingle.support.ServiceCaseHistory;

/** 보조 9표의 실제 JPA 저장 증거다. 해당 API 구현 또는 전체 22표 통과로 계산하지 않는다. */
class SupplementalStorageMappingTest {
	private static final List<String> TABLES = List.of("chat_report", "chat_report_message", "chat_safety_notice",
		"matching_preference", "operator_audit", "personality", "service_case", "service_case_history", "user_setting");
	private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");

	private static final JdbcConnectionDetails POSTGRES = SharedPostgres.newDatabase();

	@Test
	@DisplayName("SQL 선생성 없이 9표를 만들고 분석·신고 연결·안전안내의 실제 유일성을 지킨다")
	void generatesModelsAndEnforcesIndependentStorageKeys() {
		Fixture fixture = emptySchema();
		try (Factory factory = boot(fixture, "create-drop")) {
			assertTables(fixture.jdbc());
			Long personalityId = fixture.jdbc().queryForObject("insert into personality "
				+ "(survey_id,analysis_version,status,result) values (10,'v1','SUCCEEDED','{\"score\":1}') "
				+ "returning id", Long.class);
			Personality personality = factory.sessions().fromTransaction(
				session -> session.find(Personality.class, personalityId));
			assertThat(personality.getStatus()).isEqualTo(Personality.AnalysisStatus.SUCCEEDED);
			assertThat(personality.getResult()).contains("\"score\"");
			assertThatThrownBy(() -> fixture.jdbc().update("insert into personality "
				+ "(survey_id,analysis_version,status) values (10,'v1','FAILED')"))
				.isInstanceOf(DataIntegrityViolationException.class);
			fixture.jdbc().update("insert into chat_report_message(report_id,message_id) values (100,200)");
			ChatReportMessage edge = factory.sessions().fromTransaction(
				session -> session.find(ChatReportMessage.class, new ChatReportMessage.Key(100L, 200L)));
			assertThat(edge.getMessageId()).isEqualTo(200);
			assertThatThrownBy(() -> fixture.jdbc().update(
				"insert into chat_report_message(report_id,message_id) values (100,200)"))
				.isInstanceOf(DataIntegrityViolationException.class);
			fixture.jdbc().update("insert into chat_safety_notice "
				+ "(chat_participant_id,notice_type,created_at) values (100,'MONEY_REQUEST',?)", Timestamp.from(NOW));
			assertThatThrownBy(() -> fixture.jdbc().update("insert into chat_safety_notice "
				+ "(chat_participant_id,notice_type,created_at) values (100,'MONEY_REQUEST',?)", Timestamp.from(NOW)))
				.isInstanceOf(DataIntegrityViolationException.class);
		}
		assertThat(fixture.jdbc().queryForObject("select count(*) from information_schema.tables "
			+ "where table_schema=current_schema() and table_type='BASE TABLE'", Integer.class)).isZero();
	}

	@Test
	@DisplayName("두 번의 update와 validate가 운영·지원·설정 기록과 생성 시각을 보존한다")
	void preservesSupplementalRecordsAcrossUpdateAndValidate() {
		Fixture fixture = emptySchema();
		Map<String, Object> history;
		try (Factory ignored = boot(fixture, "update")) {
			assertTables(fixture.jdbc());
			fixture.jdbc().update("insert into service_case_history "
				+ "(service_case_id,event,actor_user_id,message,created_at) values (100,'ACTION_DONE',9,'kept',?)",
				Timestamp.from(NOW));
			fixture.jdbc().update("insert into user_setting "
				+ "(user_id,vibration_enabled,led_enabled,settings_version) values (9,false,true,7)");
			fixture.jdbc().update("insert into operator_audit "
				+ "(operator_id,kind,action,result_code,occurred_at) values (9,'ACCESS','MEMBER_VIEW','DENIED',?)",
				Timestamp.from(NOW));
			history = fixture.jdbc().queryForMap("select * from service_case_history");
		}
		try (Factory ignored = boot(fixture, "update")) {
			assertTables(fixture.jdbc());
		}
		try (Factory factory = boot(fixture, "validate")) {
			UserSetting setting = factory.sessions().fromTransaction(session -> session.find(UserSetting.class, 9L));
			assertThat(setting.getSettingsVersion()).isEqualTo(7);
			assertThat(setting.getVibrationEnabled()).isFalse();
			assertThat(fixture.jdbc().queryForMap("select * from service_case_history")).isEqualTo(history);
			assertThat(fixture.jdbc().queryForObject("select result_code::text from operator_audit", String.class))
				.isEqualTo("DENIED");
		}
	}

	private void assertTables(JdbcTemplate jdbc) {
		assertThat(jdbc.queryForList("select table_name from information_schema.tables "
			+ "where table_schema=current_schema() and table_type='BASE TABLE' order by table_name", String.class))
			.isEqualTo(TABLES);
		assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
			+ "where table_schema=current_schema()", Integer.class)).isEqualTo(59);
		assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
			+ "where constraint_schema=current_schema() and constraint_type='FOREIGN KEY'", Integer.class)).isZero();
	}

	private Fixture emptySchema() {
		String schema = "supplemental_" + UUID.randomUUID().toString().replace("-", "");
		JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(), POSTGRES.getPassword()));
		admin.execute("create schema " + schema);
		String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
			+ "currentSchema=" + schema;
		return new Fixture(url, schema, new JdbcTemplate(new DriverManagerDataSource(url,
			POSTGRES.getUsername(), POSTGRES.getPassword())));
	}

	private Factory boot(Fixture fixture, String mode) {
		StandardServiceRegistry registry = new StandardServiceRegistryBuilder().applySettings(Map.of(
			"jakarta.persistence.jdbc.url", fixture.url(), "jakarta.persistence.jdbc.user", POSTGRES.getUsername(),
			"jakarta.persistence.jdbc.password", POSTGRES.getPassword(), "hibernate.default_schema", fixture.schema(),
			"hibernate.dialect", NoColumnCheckPostgreSqlDialect.class.getName(), "hibernate.hbm2ddl.auto", mode,
			"hibernate.hbm2ddl.halt_on_error", true)).build();
		try {
			SessionFactory sessions = new MetadataSources(registry).addAnnotatedClass(Personality.class)
				.addAnnotatedClass(OperatorAudit.class).addAnnotatedClass(ServiceCase.class)
				.addAnnotatedClass(ServiceCaseHistory.class).addAnnotatedClass(ChatReport.class)
				.addAnnotatedClass(ChatReportMessage.class).addAnnotatedClass(ChatSafetyNotice.class)
				.addAnnotatedClass(UserSetting.class).addAnnotatedClass(MatchingPreference.class).getMetadataBuilder()
				.applyPhysicalNamingStrategy(new PhysicalNamingStrategySnakeCaseImpl()).build().buildSessionFactory();
			return new Factory(sessions, registry);
		} catch (RuntimeException failure) {
			StandardServiceRegistryBuilder.destroy(registry);
			throw failure;
		}
	}

	private record Fixture(String url, String schema, JdbcTemplate jdbc) {
	}

	private record Factory(SessionFactory sessions, StandardServiceRegistry registry) implements AutoCloseable {
		@Override
		public void close() {
			sessions.close();
			StandardServiceRegistryBuilder.destroy(registry);
		}
	}
}
