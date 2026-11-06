package com.e106.tingle.survey;

import static com.e106.tingle.CanonicalStorageDesign.totalTables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.rule.RuleArtifact;
import com.e106.tingle.rule.RuleArtifactRepository;
import com.e106.tingle.survey.dto.SurveySessionResponse;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = SurveyStoragePersistenceTest.Config.class, properties = {
	"spring.main.web-application-type=none", "spring.profiles.active=test",
	"spring.jpa.hibernate.ddl-auto=validate", "spring.docker.compose.enabled=false",
	"spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:database/schema.sql",
	"app.survey.current-version=SIS-Q-1.1-100"
})
class SurveyStoragePersistenceTest {

	private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
	private static final String VERSION = "SIS-Q-1.1-100";

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = {User.class, SurveyResponse.class, RuleArtifact.class})
	@EnableJpaRepositories(basePackageClasses = {UserRepository.class, SurveyResponseRepository.class,
		RuleArtifactRepository.class})
	@Import({SurveyService.class, SurveyCatalog.class})
	static class Config {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer("postgres:17");
		}

		@Bean
		JsonMapper jsonMapper() {
			return JsonMapper.builder().build();
		}

		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	@Autowired
	private SurveyService service;
	@Autowired
	private SurveyAnswerRepository answers;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JsonMapper mapper;

	@Test
	@DisplayName("25개 실제 설계에서 숫자 회차만 만들고 옛 칸(문항 사본·metadata·요청 키)은 쓰지 않는다")
	void createsCanonicalSessionAndFreezesItsDefinition() {
		long userId = registeredMember();
		SurveySessionResponse session = service.start(userId, VERSION);
		assertThat(session.sessionId()).isPositive();
		assertThat(session.answers()).isEmpty();
		assertThat(session.questionCount()).isEqualTo(100);
		assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='public' "
			+ "and table_type='BASE TABLE'", Integer.class)).isEqualTo(totalTables());
		assertThat(jdbc.queryForObject("select to_regclass('public.survey_responses')::text", String.class))
			.isNull();
		assertThat(jdbc.queryForMap("select legacy_public_id,client_request_id,response_context,definition_snapshot,"
			+ "revision from survey where id=?", session.sessionId()))
			.containsEntry("legacy_public_id", null).containsEntry("client_request_id", null)
			.containsEntry("response_context", null).containsEntry("definition_snapshot", null)
			.containsEntry("revision", 0L);
	}

	@Test
	@DisplayName("작성 중 회차는 다시 시작해도 이어가고, 완료 뒤에는 새 회차를 만든다. 남의 회차는 404")
	void resumesDraftAndStartsNewAfterCompletion() {
		long userId = registeredMember();
		SurveySessionResponse first = service.start(userId, VERSION);
		assertThat(service.start(userId, VERSION).sessionId()).isEqualTo(first.sessionId());
		jdbc.update("update survey set completed_at=? where id=?", Timestamp.from(NOW), first.sessionId());
		assertThat(service.get(userId, first.sessionId()).completedAt()).isEqualTo(NOW);
		assertThat(service.start(userId, VERSION).sessionId()).isNotEqualTo(first.sessionId());
		assertThatThrownBy(() -> service.get(registeredMember(), first.sessionId()))
			.isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
	}

	@Test
	@DisplayName("문항별 행에서 숫자·문자·참거짓·null 타입과 미상 답변 시각을 그대로 복원한다")
	void readsTypedScalarAnswersFromDedicatedRows() {
		long userId = registeredMember();
		long id = service.start(userId, VERSION).sessionId();
		answers.saveAllAndFlush(List.of(
			SurveyAnswer.of(id, "Q001", mapper.readTree("4"), null),
			SurveyAnswer.of(id, "Q002", mapper.readTree("\"4\""), null),
			SurveyAnswer.of(id, "Q003", mapper.readTree("false"), NOW),
			SurveyAnswer.of(id, "Q004", mapper.readTree("null"), null)));
		SurveySessionResponse view = service.get(userId, id);
		assertThat(view.answeredCount()).isEqualTo(4);
		assertThat(view.nextQuestionId()).isEqualTo("Q005");
		assertThat(view.answers().get(0).value().isNumber()).isTrue();
		assertThat(view.answers().get(1).value().isString()).isTrue();
		assertThat(view.answers().get(1).value().asString()).isEqualTo("4");
		assertThat(view.answers().get(2).value().isBoolean()).isTrue();
		assertThat(view.answers().get(3).value().isNull()).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from survey_answer where survey_id=? "
			+ "and answered_at is null", Integer.class, id)).isEqualTo(3);
	}

	@Test
	@DisplayName("과거 정의·타입 증거가 없거나 복합·초과 답이면 조용히 추정하거나 잘라 저장하지 않는다")
	void refusesUnknownOrUnrepresentableEvidence() {
		long userId = registeredMember();
		long id = service.start(userId, VERSION).sessionId();
		assertThatThrownBy(() -> SurveyAnswer.of(id, "Q001", mapper.readTree("{\"x\":1}"), NOW))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(SurveyAnswer.of(id, "Q001", mapper.readTree("[1,\"a\"]"), NOW).value(mapper))
			.isEqualTo(mapper.readTree("[1,\"a\"]"));
		assertThatThrownBy(() -> SurveyAnswer.of(id, "Q001",
			mapper.readTree(mapper.writeValueAsString("x".repeat(101))), NOW))
			.isInstanceOf(IllegalArgumentException.class);
		jdbc.update("insert into survey_answer(survey_id,question_id,answer_value) values (?,'Q001','4')", id);
		assertThatThrownBy(() -> service.get(userId, id)).isInstanceOfSatisfying(BusinessException.class,
			error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
		jdbc.update("delete from survey_answer where survey_id=?", id);
		jdbc.update("update survey set survey_version='missing-version' where id=?", id);
		assertThatThrownBy(() -> service.get(userId, id)).isInstanceOfSatisfying(BusinessException.class,
			error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
	}

	@Test
	@DisplayName("실제 회원 잠금으로 동시 시작을 직렬화하며 작성 중 회차는 하나만 생긴다")
	void serializesConcurrentSessionStarts() throws Exception {
		long userId = registeredMember();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch go = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> {
				ready.countDown();
				go.await(10, TimeUnit.SECONDS);
				return service.start(userId, VERSION).sessionId();
			});
			var second = executor.submit(() -> {
				ready.countDown();
				go.await(10, TimeUnit.SECONDS);
				return service.start(userId, VERSION).sessionId();
			});
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			go.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
		}
		assertThat(jdbc.queryForObject("select count(*) from survey where user_id=?", Integer.class, userId))
			.isEqualTo(1);
	}

	private long registeredMember() {
		Long userId = jdbc.queryForObject("insert into users(provider,provider_user_id,status,created_at) "
			+ "values ('GOOGLE',?,'ACTIVE',?) returning id", Long.class,
			UUID.randomUUID().toString(), Timestamp.from(NOW));
		jdbc.update("insert into profile(user_id,nickname,birth_date,gender,smoking_status,drinking_frequency,"
			+ "completed_at) values (?,?,date '2001-03-15','FEMALE','NON_SMOKER','NEVER',?)",
			userId, "설문" + userId, Timestamp.from(NOW));
		return userId;
	}
}
