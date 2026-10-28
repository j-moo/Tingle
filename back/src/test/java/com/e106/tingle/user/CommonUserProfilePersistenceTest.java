package com.e106.tingle.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.e106.tingle.auth.SocialProvider;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.profile.PublicProfile;
import com.e106.tingle.profile.PublicProfileRepository;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = CommonUserProfilePersistenceTest.Config.class, properties = {
	"spring.main.web-application-type=none", "spring.profiles.active=test",
	"spring.jpa.hibernate.ddl-auto=validate", "spring.docker.compose.enabled=false",
	"spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:common-user-profile-151.sql"
})
class CommonUserProfilePersistenceTest {
	private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = {User.class, PublicProfile.class})
	@EnableJpaRepositories(basePackageClasses = {UserRepository.class, PublicProfileRepository.class})
	@Import(ProfileService.class)
	static class Config {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer("postgres:17");
		}

		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	@Autowired
	private UserRepository users;
	@Autowired
	private ProfileRepository profiles;
	@Autowired
	private PublicProfileRepository publicProfiles;
	@Autowired
	private ProfileService service;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	@DisplayName("회원 native insert는 named enum과 소셜 유일성을 지키고 일괄 동의를 추정하지 않는다")
	void insertsCanonicalUserWithoutInventingAggregateConsent() {
		String subject = UUID.randomUUID().toString();
		users.insertIfAbsent("GOOGLE", subject, NOW);
		users.insertIfAbsent("GOOGLE", subject, NOW.plusSeconds(30));
		User user = users.findByProviderAndProviderSubject(SocialProvider.GOOGLE, subject).orElseThrow();
		assertThat(user.getId()).isPositive();
		assertThat(user.getCreatedAt()).isEqualTo(NOW);
		assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(user.getAgreedAt()).isNull();
		assertThat(user.isOperator()).isFalse();
		assertThat(jdbc.queryForObject("select count(*) from users where provider_user_id=?", Long.class, subject))
			.isEqualTo(1L);
	}

	@Test
	@DisplayName("기존 정지·탈퇴 상태와 생성·가입 완료 시각은 이름 변경 후에도 유지한다")
	void preservesAccountStatesAndFirstCompletion() {
		for (UserStatus status : List.of(UserStatus.SUSPENDED, UserStatus.WITHDRAWN)) {
			long id = user();
			Instant first = NOW.minusSeconds(3600);
			jdbc.update("update users set status=?,signup_completed_at=? where id=?",
				status.name(), Timestamp.from(first), id);
			new TransactionTemplate(transactionManager).executeWithoutResult(tx -> users.markSignupCompleted(id, NOW));
			User saved = users.findById(id).orElseThrow();
			assertThat(saved.getStatus()).isEqualTo(status);
			assertThat(saved.getCreatedAt()).isEqualTo(NOW);
			assertThat(saved.getSignupCompletedAt()).isEqualTo(first);
		}
	}

	@Test
	@DisplayName("초안은 자동 완료되지 않고 명시적 등록·재등록은 최초 완료시각을 유지한다")
	void separatesDraftFromExplicitRegistration() {
		long id = user();
		String nickname = "Draft" + id;
		service.patch(id, jsonMapper.valueToTree(Map.of("nickname", nickname, "gender", "FEMALE",
			"birthDate", "1995-01-02", "smokingStatus", "NON_SMOKER", "drinkingFrequency", "NEVER")));
		assertThat(profiles.findById(id).orElseThrow().isRegistered()).isFalse();
		service.register(id);
		Instant first = NOW.minusSeconds(60);
		jdbc.update("update profile set completed_at=? where user_id=?", Timestamp.from(first), id);
		service.register(id);
		assertThat(profiles.findById(id).orElseThrow().getCompletedAt()).isEqualTo(first);
		assertThatThrownBy(() -> service.patch(id, jsonMapper.valueToTree(Map.of("gender", "MALE"))))
			.isInstanceOf(BusinessException.class);
	}

	@Test
	@DisplayName("공개·본인 프로필은 같은 표의 enum·사진·유효 분석을 읽고 원래 닉네임을 바꾸지 않는다")
	void readsOneCanonicalProfileThroughBothMappings() {
		long id = user();
		String nickname = "Original" + id;
		String photo = "https://photos.example/profile/" + id + "/photo.webp";
		jdbc.update("insert into profile(user_id,nickname,gender,smoking_status,drinking_frequency,religion,"
			+ "photo_url,active_personality_id) values (?,?,'MALE','NON_SMOKER','NEVER','UNDISCLOSED',?,91)",
			id, nickname, photo);
		Profile own = profiles.findById(id).orElseThrow();
		PublicProfile shared = publicProfiles.findById(id).orElseThrow();
		assertThat(own.getActivePersonalityId()).isEqualTo(91L);
		assertThat(own.getImageUrl()).isEqualTo(photo);
		assertThat(shared.getImageUrl()).isEqualTo(photo);
		assertThat(shared.getGender()).isEqualTo("MALE");
		assertThat(shared.getSmokingStatus()).isEqualTo("NON_SMOKER");
		assertThat(shared.getReligion()).isEqualTo("UNDISCLOSED");
		assertThat(profiles.findByNicknameIgnoreCase(nickname.toUpperCase(java.util.Locale.ROOT)))
			.get().extracting(Profile::getNickname).isEqualTo(nickname);
		assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name='profile' "
			+ "and column_name='nickname_key'", Integer.class)).isZero();
	}

	@Test
	@DisplayName("서로 다른 회원의 동시 대소문자 닉네임 등록은 DB 인덱스에서 한 명만 허용한다")
	void enforcesLowerNicknameUniquenessAcrossConcurrentTransactions() throws Exception {
		long first = user();
		long second = user();
		String nickname = "Race" + first;
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var firstResult = executor.submit(() -> insertNickname(first, nickname, start));
			var secondResult = executor.submit(() -> insertNickname(second,
				nickname.toUpperCase(java.util.Locale.ROOT), start));
			start.countDown();
			assertThat(List.of(firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(true, false);
		}
		assertThat(jdbc.queryForObject("select count(*) from profile where lower(nickname)=lower(?)",
			Integer.class, nickname)).isEqualTo(1);
	}

	private boolean insertNickname(long userId, String nickname, CountDownLatch start) throws InterruptedException {
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("닉네임 동시 등록 시작 대기 만료");
		}
		try {
			jdbc.update("insert into profile(user_id,nickname) values (?,?)", userId, nickname);
			return true;
		} catch (DataIntegrityViolationException duplicate) {
			return false;
		}
	}

	private long user() {
		String subject = UUID.randomUUID().toString();
		users.insertIfAbsent("KAKAO", subject, NOW);
		return users.findByProviderAndProviderSubject(SocialProvider.KAKAO, subject).orElseThrow().getId();
	}
}
