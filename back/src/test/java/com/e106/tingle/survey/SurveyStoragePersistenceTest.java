package com.e106.tingle.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.survey.dto.SurveySessionResponse;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = SurveyStoragePersistenceTest.Config.class, properties = {
	"spring.main.web-application-type=none", "spring.profiles.active=test",
	"spring.jpa.hibernate.ddl-auto=validate", "spring.docker.compose.enabled=false",
	"spring.sql.init.mode=always", "spring.sql.init.schema-locations=file:database/schema.sql",
	"app.survey.current-version=SIS-Q-1.1-100"
})
class SurveyStoragePersistenceTest {

	private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
	private static final String VERSION = "SIS-Q-1.1-100";

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = {User.class, SurveyResponse.class})
	@EnableJpaRepositories(basePackageClasses = {UserRepository.class, SurveyResponseRepository.class})
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
	}

	@Autowired
	private SurveyService service;
	@Autowired
	private SurveyAnswerRepository answers;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JsonMapper mapper;
	@MockitoSpyBean
	private SurveyCatalog catalog;

	@Test
	@DisplayName("22개 실제 설계에서 숫자 회차와 신규 불변 정의를 저장하고 구 aggregate 표는 쓰지 않는다")
	void createsCanonicalSessionAndFreezesItsDefinition() {
		long userId = registeredMember();
		SurveySessionResponse session = service.start(userId, UUID.randomUUID(), VERSION);
		assertThat(session.sessionId()).isPositive();
		assertThat(session.revision()).isZero();
		assertThat(session.answers()).isEmpty();
		assertThat(session.questionCount()).isEqualTo(100);
		assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='public' "
			+ "and table_type='BASE TABLE'", Integer.class)).isEqualTo(22);
		assertThat(jdbc.queryForObject("select to_regclass('public.survey_responses')::text", String.class))
			.isNull();
		assertThat(jdbc.queryForMap("select legacy_public_id,"
			+ "jsonb_exists(response_context,'answers') as hidden_answers,"
			+ "definition_snapshot is not null as frozen from survey where id=?", session.sessionId()))
			.containsEntry("legacy_public_id", null).containsEntry("hidden_answers", false)
			.containsEntry("frozen", true);
	}

	@Test
	@DisplayName("같은 요청·이어하기 키는 기존 숫자 회차와 변경 번호를 보존하고 완료 뒤에도 재사용한다")
	void preservesCurrentIdempotentResumeAndCompletionState() {
		long userId = registeredMember();
		UUID initial = UUID.randomUUID();
		SurveySessionResponse first = service.start(userId, initial, VERSION);
		assertThat(service.start(userId, initial, VERSION).sessionId()).isEqualTo(first.sessionId());
		UUID resumed = UUID.randomUUID();
		assertThat(service.start(userId, resumed, VERSION).revision()).isEqualTo(first.revision());
		jdbc.update("update survey set completed_at=?,revision=7 where id=?", Timestamp.from(NOW), first.sessionId());
		SurveySessionResponse completed = service.start(userId, resumed, VERSION);
		assertThat(completed.sessionId()).isEqualTo(first.sessionId());
		assertThat(completed.status()).isEqualTo("COMPLETED");
		assertThat(completed.completedAt()).isEqualTo(NOW);
		assertThat(completed.revision()).isEqualTo(7);
		assertThatThrownBy(() -> service.get(registeredMember(), first.sessionId()))
			.isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
	}

	@Test
	@DisplayName("문항별 행에서 숫자·문자·참거짓·null 타입과 미상 답변 시각을 그대로 복원한다")
	void readsTypedScalarAnswersFromDedicatedRows() {
		long userId = registeredMember();
		long id = service.start(userId, UUID.randomUUID(), VERSION).sessionId();
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
	@DisplayName("새 회차는 현재 카탈로그 조회가 사라져도 저장한 원문·manifest·문항 순서를 유지한다")
	void retrievesFrozenQuestionnaireWithoutReplacingItFromCurrentFiles() {
		long userId = registeredMember();
		SurveySessionResponse first = service.start(userId, UUID.randomUUID(), VERSION);
		doReturn(Optional.empty()).when(catalog).find(VERSION);
		assertThat(service.get(userId, first.sessionId()).manifest()).isEqualTo(first.manifest());
		assertThat(service.questionnaireOf(userId, first.sessionId()).questions()).hasSize(100);
		assertThat(service.questionnaireOf(userId, first.sessionId()).metadataSchema()).isNotNull();
	}

	@Test
	@DisplayName("과거 정의·타입 증거가 없거나 복합·초과 답이면 조용히 추정하거나 잘라 저장하지 않는다")
	void refusesUnknownOrUnrepresentableEvidence() {
		long userId = registeredMember();
		long id = service.start(userId, UUID.randomUUID(), VERSION).sessionId();
		assertThatThrownBy(() -> SurveyAnswer.of(id, "Q001", mapper.readTree("{\"x\":1}"), NOW))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SurveyAnswer.of(id, "Q001",
			mapper.readTree(mapper.writeValueAsString("x".repeat(101))), NOW))
			.isInstanceOf(IllegalArgumentException.class);
		jdbc.update("insert into survey_answer(survey_id,question_id,answer_value) values (?,'Q001','4')", id);
		assertThatThrownBy(() -> service.get(userId, id)).isInstanceOfSatisfying(BusinessException.class,
			error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
		jdbc.update("delete from survey_answer where survey_id=?", id);
		jdbc.update("update survey set definition_snapshot=null,survey_version='missing-version' where id=?", id);
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
				return service.start(userId, UUID.randomUUID(), VERSION).sessionId();
			});
			var second = executor.submit(() -> {
				ready.countDown();
				go.await(10, TimeUnit.SECONDS);
				return service.start(userId, UUID.randomUUID(), VERSION).sessionId();
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
