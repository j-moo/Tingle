package com.e106.tingle.global.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.e106.tingle.agreement.UserConsent;
import com.e106.tingle.auth.RefreshToken;
import com.e106.tingle.auth.SocialProvider;
import com.e106.tingle.profile.PublicProfile;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.push.PushInstallation;
import com.e106.tingle.survey.SurveyResponse;
import com.e106.tingle.user.Profile;
import com.e106.tingle.user.User;

/** SQL 선생성 없이 공통 실제 엔티티 5표만 검증한다. 전체 canonical 22표 증거와 구분한다. */
@Testcontainers
class JpaSchemaGenerationTest {
	private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
	private static final List<String> ENUMS = List.of("auth_provider", "device_platform", "drinking_frequency",
		"gender", "religion", "smoking_status");

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

	@Test
	@DisplayName("빈 PostgreSQL에 순수 JPA create-drop으로 표·canonical enum·실제 닉네임 유일성을 만든다")
	void createsMappedSchemaWithoutSqlBootstrapAndDropsOnlyTables() throws Exception {
		Fixture fixture = emptySchema();
		try (Factory factory = boot(fixture, "create-drop")) {
			assertCanonicalSchema(fixture.jdbc());
			assertThat(factory.sessions().getMetamodel().entity(User.class)).isNotNull();
			assertThat(new NoColumnCheckPostgreSqlDialect().getEnumTypeDeclaration(SocialProvider.class))
				.isEqualTo("auth_provider");
			assertThat(fixture.jdbc().queryForList("select enumlabel from pg_enum "
				+ "where enumtypid='gender'::regtype order by enumsortorder", String.class))
				.containsExactly("MALE", "FEMALE");
			factory.sessions().inTransaction(session -> assertThat(session.createSelectionQuery(
				"select u from User u where u.provider=com.e106.tingle.auth.SocialProvider.KAKAO", User.class)
				.getResultList()).isEmpty());
			assertConcurrentNicknameRejection(fixture.jdbc());
		}
		assertThat(fixture.jdbc().queryForObject("select count(*) from information_schema.tables "
			+ "where table_schema=current_schema() and table_type='BASE TABLE'", Integer.class)).isZero();
		// update uses the same auxiliary drop hook: retaining types prevents DROP TYPE CASCADE on real columns.
		assertEnumNames(fixture.jdbc());
	}

	@Test
	@DisplayName("두 번의 JPA update와 뒤이은 validate는 표·enum·index OID와 회원·철회 이력을 보존한다")
	void updatesAndValidatesWithoutRecreatingTypesOrLosingEvidence() {
		Fixture fixture = emptySchema();
		try (Factory ignored = boot(fixture, "update")) {
			assertCanonicalSchema(fixture.jdbc());
			fixture.jdbc().update("insert into users(provider,provider_user_id,status,created_at,is_operator) "
				+ "values ('KAKAO','existing','SUSPENDED',?,false)", Timestamp.from(NOW));
			fixture.jdbc().update("insert into profile(user_id,nickname) values (1,'Original')");
			for (boolean agreed : List.of(true, false, true)) {
				fixture.jdbc().update("insert into user_consents(user_id,consent_type,document_version,agreed,"
					+ "recorded_at) values (1,'AI_RISK_DETECTION','historical',?,?)", agreed, Timestamp.from(NOW));
			}
		}
		Map<String, Object> account = fixture.jdbc().queryForMap("select * from users");
		List<Map<String, Object>> consents = fixture.jdbc()
			.queryForList("select * from user_consents order by consent_id");
		Map<String, Object> identifiers = identifiers(fixture.jdbc());
		try (Factory ignored = boot(fixture, "update")) {
			assertCanonicalSchema(fixture.jdbc());
			assertThat(identifiers(fixture.jdbc())).isEqualTo(identifiers);
		}
		try (Factory factory = boot(fixture, "validate")) {
			String status = factory.sessions().fromTransaction(
				session -> session.find(User.class, 1L).getStatus().name());
			assertThat(status).isEqualTo("SUSPENDED");
			assertThat(fixture.jdbc().queryForMap("select * from users")).isEqualTo(account);
			assertThat(fixture.jdbc().queryForList("select * from user_consents order by consent_id"))
				.isEqualTo(consents);
			assertThat(fixture.jdbc().queryForObject("select nickname from profile", String.class))
				.isEqualTo("Original");
			assertThat(identifiers(fixture.jdbc())).isEqualTo(identifiers);
		}
	}

	@Test
	@DisplayName("기존 enum 선택지가 다르면 자동 삭제·재생성·변환 없이 시작을 차단한다")
	void refusesExistingEnumMismatchWithoutDroppingIt() {
		Fixture fixture = emptySchema();
		fixture.jdbc().execute("create type auth_provider as enum ('KAKAO')");
		Long before = fixture.jdbc().queryForObject("select 'auth_provider'::regtype::oid::bigint", Long.class);
		assertThatThrownBy(() -> boot(fixture, "update")).isInstanceOf(RuntimeException.class)
			.hasStackTraceContaining("Existing enum auth_provider differs");
		assertThat(fixture.jdbc().queryForObject("select 'auth_provider'::regtype::oid::bigint", Long.class))
			.isEqualTo(before);
		assertThat(fixture.jdbc().queryForList("select enumlabel from pg_enum "
			+ "where enumtypid='auth_provider'::regtype", String.class)).containsExactly("KAKAO");
	}

	@Test
	@DisplayName("동명 index가 비유일·다른 식이면 자동 교체하지 않고 회원 데이터와 함께 보존한다")
	void refusesWrongExistingIndexWithoutReplacingIt() {
		Fixture fixture = emptySchema();
		try (Factory ignored = boot(fixture, "update")) {
			fixture.jdbc().update("insert into profile(user_id,nickname) values (1,'Original')");
		}
		// Disposable-test corruption only, never an application bootstrap operation.
		fixture.jdbc().execute("drop index uq_profile_nickname_lower");
		fixture.jdbc().execute("create index uq_profile_nickname_lower on profile(nickname)");
		Map<String, Object> before = fixture.jdbc().queryForMap("select 'uq_profile_nickname_lower'::regclass::oid "
			+ "as id,pg_get_indexdef('uq_profile_nickname_lower'::regclass) as definition");
		assertThatThrownBy(() -> boot(fixture, "update")).isInstanceOf(RuntimeException.class)
			.hasStackTraceContaining("uq_profile_nickname_lower is incompatible");
		assertThat(fixture.jdbc().queryForMap("select 'uq_profile_nickname_lower'::regclass::oid "
			+ "as id,pg_get_indexdef('uq_profile_nickname_lower'::regclass) as definition")).isEqualTo(before);
		assertThat(fixture.jdbc().queryForObject("select nickname from profile", String.class)).isEqualTo("Original");
		assertThatThrownBy(() -> boot(fixture, "validate")).isInstanceOf(RuntimeException.class)
			.hasStackTraceContaining("uq_profile_nickname_lower is incompatible");
	}

	@Test
	@DisplayName("validate도 enum 불일치를 읽기 전용으로 차단하고 기존 선택지·표를 변경하지 않는다")
	void rejectsEnumMismatchDuringReadOnlyValidation() {
		Fixture fixture = emptySchema();
		try (Factory ignored = boot(fixture, "update")) {
			fixture.jdbc().update("insert into profile(user_id,nickname) values (1,'Original')");
		}
		fixture.jdbc().execute("alter type auth_provider add value 'UNEXPECTED'");
		Map<String, Object> before = identifiers(fixture.jdbc());
		assertThatThrownBy(() -> boot(fixture, "validate")).isInstanceOf(RuntimeException.class)
			.hasStackTraceContaining("Existing enum auth_provider differs");
		assertThat(identifiers(fixture.jdbc())).isEqualTo(before);
		assertThat(fixture.jdbc().queryForObject("select nickname from profile", String.class)).isEqualTo("Original");
	}

	@Test
	@DisplayName("실제 survey 엔티티의 JPA 부분 유일성은 중복 초안을 막고 완료 후 새 회차는 허용한다")
	void generatesSurveyDraftPartialUniquenessForActualModel() {
		Fixture fixture = emptySchema();
		try (Factory ignored = boot(fixture, "update", SurveyResponse.class)) {
			fixture.jdbc().update("insert into survey(user_id,survey_version,revision) values (41,'SIS-Q-1.1-100',0)");
			assertThatThrownBy(() -> fixture.jdbc().update("insert into survey(user_id,survey_version,revision) "
				+ "values (41,'SIS-Q-1.1-100',0)")).isInstanceOf(DataIntegrityViolationException.class);
			fixture.jdbc().update("update survey set completed_at=? where user_id=41", Timestamp.from(NOW));
			fixture.jdbc().update("insert into survey(user_id,survey_version,revision) values (41,'SIS-Q-1.1-100',0)");
			assertThat(fixture.jdbc().queryForObject("select count(*) from survey where user_id=41", Integer.class))
				.isEqualTo(2);
		}
		Long before = fixture.jdbc().queryForObject("select 'uq_survey_draft'::regclass::oid::bigint", Long.class);
		try (Factory ignored = boot(fixture, "update", SurveyResponse.class)) {
			assertThat(fixture.jdbc().queryForObject("select 'uq_survey_draft'::regclass::oid::bigint", Long.class))
				.isEqualTo(before);
		}
	}

	@Test
	@DisplayName("설문 동명 인덱스의 부분 조건이 없으면 update·validate 모두 교체 없이 거부한다")
	void rejectsSurveyIndexWithWrongPredicate() {
		Fixture fixture = emptySchema();
		try (Factory ignored = boot(fixture, "update", SurveyResponse.class)) {
			fixture.jdbc().update("insert into survey(user_id,survey_version,revision) values (41,'SIS-Q-1.1-100',0)");
		}
		fixture.jdbc().execute("drop index uq_survey_draft");
		fixture.jdbc().execute("create unique index uq_survey_draft on survey(user_id,survey_version)");
		Long before = fixture.jdbc().queryForObject("select 'uq_survey_draft'::regclass::oid::bigint", Long.class);
		for (String mode : List.of("update", "validate")) {
			assertThatThrownBy(() -> boot(fixture, mode, SurveyResponse.class)).isInstanceOf(RuntimeException.class)
				.hasStackTraceContaining("uq_survey_draft is incompatible");
			assertThat(fixture.jdbc().queryForObject("select 'uq_survey_draft'::regclass::oid::bigint", Long.class))
				.isEqualTo(before);
		}
	}

	@Test
	@DisplayName("JPA matching은 회원 참조 없는 CLOSED 삭제 작업 행만 허용한다")
	void generatesNullableClosedMatchingTombstone() {
		Fixture fixture = emptySchema();
		try (Factory factory = boot(fixture, "create-drop", MatchProposal.class)) {
			assertThat(fixture.jdbc().queryForList("select is_nullable from information_schema.columns "
				+ "where table_schema=current_schema() and table_name='matching' "
				+ "and column_name in ('user_low_id','user_high_id') order by column_name", String.class))
				.containsExactly("YES", "YES");
			Long id = fixture.jdbc().queryForObject("insert into matching(status) values ('CLOSED') returning id",
				Long.class);
			MatchProposal tombstone = factory.sessions()
				.fromTransaction(session -> session.find(MatchProposal.class, id));
			assertThat(tombstone.hasMembers()).isFalse();
			assertThat(tombstone.isProposed()).isFalse();
			assertThatThrownBy(() -> fixture.jdbc().update("insert into matching(status) values ('PENDING')"))
				.isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	private void assertCanonicalSchema(JdbcTemplate jdbc) {
		assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
			+ "where table_schema=current_schema() and table_type='BASE TABLE'", Integer.class)).isEqualTo(5);
		assertEnumNames(jdbc);
		assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
			+ "where table_schema=current_schema() and table_name='users' and column_name='provider' "
			+ "and udt_name='auth_provider'", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select indisunique and indisvalid from pg_index "
			+ "where indexrelid='uq_profile_nickname_lower'::regclass", Boolean.class)).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from pg_constraint "
			+ "where conrelid='users'::regclass and contype='c'",
			Integer.class)).isZero();
	}

	private void assertEnumNames(JdbcTemplate jdbc) {
		assertThat(jdbc.queryForList("select t.typname from pg_type t join pg_namespace n on n.oid=t.typnamespace "
			+ "where n.nspname=current_schema() and t.typtype='e' order by t.typname", String.class))
			.isEqualTo(ENUMS);
	}

	private Map<String, Object> identifiers(JdbcTemplate jdbc) {
		return jdbc.queryForMap("select 'users'::regclass::oid::bigint as users, "
			+ "'auth_provider'::regtype::oid::bigint as provider, "
			+ "'uq_profile_nickname_lower'::regclass::oid::bigint as nickname_index, "
			+ "(select atttypid::bigint from pg_attribute where attrelid='users'::regclass "
			+ "and attname='provider') as column_type");
	}

	private void assertConcurrentNicknameRejection(JdbcTemplate jdbc) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var first = executor.submit(() -> insertNickname(jdbc, 1, "SameName", start));
			var second = executor.submit(() -> insertNickname(jdbc, 2, "SAMENAME", start));
			start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(true, false);
		}
	}

	private boolean insertNickname(JdbcTemplate jdbc, long userId, String nickname, CountDownLatch start)
		throws InterruptedException {
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 닉네임 등록 시작 대기 만료");
		}
		try {
			jdbc.update("insert into profile(user_id,nickname) values (?,?)", userId, nickname);
			return true;
		} catch (DataIntegrityViolationException duplicate) {
			return false;
		}
	}

	private Fixture emptySchema() {
		String schema = "jpa_" + UUID.randomUUID().toString().replace("-", "");
		JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(), POSTGRES.getPassword()));
		admin.execute("create schema " + schema);
		String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
			+ "currentSchema=" + schema;
		return new Fixture(url, schema, new JdbcTemplate(new DriverManagerDataSource(url,
			POSTGRES.getUsername(), POSTGRES.getPassword())));
	}

	private Factory boot(Fixture fixture, String mode, Class<?>... extraEntities) {
		StandardServiceRegistry registry = new StandardServiceRegistryBuilder().applySettings(Map.of(
			"jakarta.persistence.jdbc.url", fixture.url(),
			"jakarta.persistence.jdbc.user", POSTGRES.getUsername(),
			"jakarta.persistence.jdbc.password", POSTGRES.getPassword(),
			"hibernate.dialect", NoColumnCheckPostgreSqlDialect.class.getName(),
			"hibernate.hbm2ddl.auto", mode,
			"hibernate.hbm2ddl.halt_on_error", true,
			"hibernate.default_schema", fixture.schema())).build();
		try {
			MetadataSources sources = new MetadataSources(registry).addAnnotatedClass(User.class)
				.addAnnotatedClass(Profile.class).addAnnotatedClass(PublicProfile.class)
				.addAnnotatedClass(UserConsent.class).addAnnotatedClass(PushInstallation.class)
				.addAnnotatedClass(RefreshToken.class);
			for (Class<?> extra : extraEntities) {
				sources.addAnnotatedClass(extra);
			}
			SessionFactory sessions = sources.getMetadataBuilder()
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
