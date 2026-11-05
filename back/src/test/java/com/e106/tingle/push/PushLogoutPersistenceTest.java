package com.e106.tingle.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.e106.tingle.auth.AuthProperties;
import com.e106.tingle.auth.RefreshToken;
import com.e106.tingle.auth.RefreshTokenRepository;
import com.e106.tingle.auth.SocialProvider;
import com.e106.tingle.auth.TokenService;
import com.e106.tingle.auth.TokenService.IssuedTokens;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.push.dto.RegisterPushRequest;
import com.e106.tingle.user.ProfileRepository;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@SpringBootTest(classes = PushLogoutPersistenceTest.Config.class, properties = {
	"spring.main.web-application-type=none", "spring.profiles.active=test",
	"spring.jpa.hibernate.ddl-auto=validate", "spring.docker.compose.enabled=false",
	"spring.sql.init.mode=always",
	"spring.sql.init.schema-locations=classpath:database/schema.sql"
})
class PushLogoutPersistenceTest {

	private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
	private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
	private final AtomicReference<Instant> time = new AtomicReference<>(NOW);

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EnableJpaRepositories(basePackages = {"com.e106.tingle.push", "com.e106.tingle.auth", "com.e106.tingle.user"},
		excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ProfileRepository.class))
	@Import({TokenService.class, PushInstallationService.class, PushDeliveryLookup.class})
	static class Config {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer("postgres:17");
		}

		@Bean
		PersistenceManagedTypes managedTypes() {
			return PersistenceManagedTypes.of(User.class.getName(), RefreshToken.class.getName(),
				PushInstallation.class.getName());
		}

		@Bean
		AuthProperties authProperties() {
			return new AuthProperties("synthetic-test-key-only-32-bytes-minimum", Duration.ofMinutes(15),
				Duration.ofDays(30), Duration.ofSeconds(30), null, null, null, null, null, "synthetic", "synthetic");
		}

		@Bean
		JwtEncoder jwtEncoder(AuthProperties properties) {
			return new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
				properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
		}

		@Bean
		PushTokenEncryption encryption() {
			return new PushTokenEncryption(KEY);
		}
	}

	@Autowired
	private TokenService tokens;
	@Autowired
	private PushInstallationService installations;
	@Autowired
	private PushDeliveryLookup deliveries;
	@Autowired
	private UserRepository users;
	@Autowired
	private JdbcTemplate jdbc;
	@MockitoSpyBean
	private RefreshTokenRepository sessions;
	@MockitoBean
	private Clock clock;

	@BeforeEach
	void useControlledServerClock() {
		time.set(NOW);
		when(clock.instant()).thenAnswer(invocation -> time.get());
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
	}

	@Test
	@DisplayName("일반 로그아웃은 같은 회원·family 연결만 원자 비우고 행·암호문·다른 회원 로그인을 보존한다")
	void normalLogoutClearsOnlyExactSession() {
		IssuedTokens first = login(newUser());
		IssuedTokens otherUser = login(newUser());
		UUID firstId = register(first, "synthetic-first");
		UUID secondId = register(first, "synthetic-second-install");
		UUID foreignId = register(otherUser, "synthetic-other-user");
		Map<String, Object> before = row(firstId);
		time.set(NOW.plusSeconds(1));
		tokens.logout(first.refreshToken());
		assertUnbound(firstId);
		assertUnbound(secondId);
		assertThat(row(firstId)).containsEntry("id", before.get("id"))
			.containsEntry("token_ciphertext", before.get("token_ciphertext"));
		assertThat(jdbc.queryForObject("select updated_at from push_installation where client_installation_id=?",
			Instant.class, firstId)).isEqualTo(NOW.plusSeconds(1));
		assertThat(jdbc.queryForObject("select revoked_at from refresh_token where family_id=?",
			Instant.class, family(first))).isEqualTo(NOW.plusSeconds(1));
		assertThat(row(foreignId)).containsEntry("user_id", otherUser.userId());
		assertThat(sessions.isSessionActive(family(first), first.userId())).isFalse();
		assertThat(sessions.isSessionActive(family(otherUser), otherUser.userId())).isTrue();
	}

	@Test
	@DisplayName("계정 전환 뒤 늦은 일반·선택 해제 재시도는 새 소유자나 새 세대를 지우지 않는다")
	void staleLogoutCannotClearNewAccountBinding() {
		IssuedTokens old = login(newUser());
		IssuedTokens current = login(newUser());
		UUID id = register(old, "synthetic-old");
		PushDeliveryLookup.Candidate queued = deliveries.candidates(old.userId(), PushNotificationKind.NEW_PROPOSAL)
			.getFirst();
		Map<String, Object> before = row(id);
		tokens.logout(old.refreshToken());
		installations.register(current.userId(), family(current),
			new RegisterPushRequest(id, PushPlatform.ANDROID, "synthetic-current"));
		Map<String, Object> reconnected = row(id);
		tokens.logout(old.refreshToken(), id);
		tokens.logout(old.refreshToken());
		assertThat(row(id)).containsEntry("id", before.get("id")).containsEntry("user_id", current.userId())
			.containsEntry("binding_id", reconnected.get("binding_id"))
			.containsEntry("token_ciphertext", reconnected.get("token_ciphertext"));
		assertThat(reconnected.get("binding_id")).isNotEqualTo(before.get("binding_id"));
		assertThat(deliveries.resolve(queued)).isEmpty();
		assertThat(new PushTokenCipher(KEY).decrypt(id, current.userId(),
			(String)row(id).get("token_ciphertext"))).isEqualTo("synthetic-current");
	}

	@Test
	@DisplayName("같은 회원의 새 family 재연결도 이전 family의 지연 로그아웃으로 비우지 않는다")
	void staleLogoutCannotClearNewFamilyForSameUser() {
		IssuedTokens old = login(newUser());
		UUID id = register(old, "synthetic-old-family");
		tokens.logout(old.refreshToken());
		IssuedTokens current = login(old.userId());
		installations.register(current.userId(), family(current),
			new RegisterPushRequest(id, PushPlatform.ANDROID, "synthetic-new-family"));
		Map<String, Object> reconnected = row(id);
		tokens.logout(old.refreshToken(), id);
		tokens.logout(old.refreshToken());
		assertThat(row(id)).isEqualTo(reconnected);
		assertThat(deliveries.candidates(current.userId(), PushNotificationKind.NEW_PROPOSAL)).hasSize(1);
	}

	@Test
	@DisplayName("호환용 releaseInstallationId가 다른 설치를 지정해도 증명의 실제 family만 종료한다")
	void optionalIdentifierCannotSelectForeignConnection() {
		IssuedTokens first = login(newUser());
		IssuedTokens foreign = login(newUser());
		UUID firstId = register(first, "synthetic-first");
		UUID foreignId = register(foreign, "synthetic-foreign");
		tokens.logout(first.refreshToken(), foreignId);
		assertUnbound(firstId);
		assertThat(row(foreignId)).containsEntry("user_id", foreign.userId());
	}

	@Test
	@DisplayName("등록과 로그아웃 경합은 회원 잠금으로 직렬화해 종료 세션의 연결을 되살리지 않는다")
	void registrationAndLogoutRaceRemainUnbound() throws Exception {
		IssuedTokens session = login(newUser());
		UUID id = register(session, "synthetic-before");
		CountDownLatch start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var registration = pool.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				try {
					installations.register(session.userId(), family(session),
						new RegisterPushRequest(id, PushPlatform.ANDROID, "synthetic-race"));
					return "REGISTERED";
				} catch (BusinessException error) {
					return error.getErrorCode().name();
				}
			});
			var logout = pool.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				tokens.logout(session.refreshToken());
				return true;
			});
			start.countDown();
			assertThat(registration.get(20, TimeUnit.SECONDS)).isIn("REGISTERED", "SESSION_REVOKED");
			assertThat(logout.get(20, TimeUnit.SECONDS)).isTrue();
		}
		assertUnbound(id);
		assertThat(deliveries.candidates(session.userId(), PushNotificationKind.NEW_PROPOSAL)).isEmpty();
	}

	@Test
	@DisplayName("탈취 감지 401도 전체 세션 폐기와 해당 회원 연결 정리를 실제 커밋한다")
	void theftRevocationCommitsUnbindingDespiteBusinessException() {
		IssuedTokens first = login(newUser());
		IssuedTokens foreign = login(newUser());
		UUID firstId = register(first, "synthetic-first");
		UUID secondId = register(first, "synthetic-another");
		UUID foreignId = register(foreign, "synthetic-foreign");
		tokens.rotate(first.refreshToken());
		time.set(NOW.plusSeconds(31));
		assertThatThrownBy(() -> tokens.rotate(first.refreshToken())).isInstanceOfSatisfying(BusinessException.class,
			error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN));
		assertUnbound(firstId);
		assertUnbound(secondId);
		assertThat(sessions.isSessionActive(family(first), first.userId())).isFalse();
		assertThat(row(foreignId)).containsEntry("user_id", foreign.userId());
	}

	@Test
	@DisplayName("세션 폐기 저장 실패는 앞서 비운 푸시 연결까지 같은 트랜잭션에서 롤백한다")
	void storageFailureRollsBackUnbinding() {
		IssuedTokens session = login(newUser());
		UUID id = register(session, "synthetic-owned");
		Map<String, Object> before = row(id);
		doThrow(new DataAccessResourceFailureException("synthetic-revocation-failure"))
			.when(sessions).revokeAllByFamilyId(family(session), NOW);
		assertThatThrownBy(() -> tokens.logout(session.refreshToken()))
			.isInstanceOf(DataAccessResourceFailureException.class);
		assertThat(row(id)).containsEntry("user_id", session.userId())
			.containsEntry("binding_id", before.get("binding_id")).containsEntry("linked_at", before.get("linked_at"));
		assertThat(sessions.isSessionActive(family(session), session.userId())).isTrue();
	}

	@Test
	@DisplayName("유효 family의 정상 재발급은 연결을 유지하지만 만료한 family는 새 제안도 수신하지 않는다")
	void rotationStaysBoundAndExpiryFailsClosed() {
		IssuedTokens session = login(newUser());
		UUID id = register(session, "synthetic-owned");
		Map<String, Object> before = row(id);
		IssuedTokens rotated = tokens.rotate(session.refreshToken());
		assertThat(family(rotated)).isEqualTo(family(session));
		assertThat(row(id)).containsEntry("binding_id", before.get("binding_id"))
			.containsEntry("linked_at", before.get("linked_at"));
		List<PushDeliveryLookup.Candidate> queued = deliveries.candidates(session.userId(),
			PushNotificationKind.NEW_PROPOSAL);
		assertThat(queued).hasSize(1);
		time.set(NOW.plus(Duration.ofDays(31)));
		assertThat(deliveries.candidates(session.userId(), PushNotificationKind.NEW_PROPOSAL)).isEmpty();
		assertThat(deliveries.resolve(queued.getFirst())).isEmpty();
	}

	private long newUser() {
		String subject = UUID.randomUUID().toString();
		users.insertIfAbsent("KAKAO", subject, NOW);
		return users.findByProviderAndProviderSubject(SocialProvider.KAKAO, subject).orElseThrow().getId();
	}

	private IssuedTokens login(long userId) {
		return tokens.issue(userId);
	}

	private UUID family(IssuedTokens session) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
				.digest(session.refreshToken().getBytes(StandardCharsets.UTF_8));
			return jdbc.queryForObject("select family_id from refresh_token where user_id=? and token_hash=?",
				UUID.class, session.userId(), hash);
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException(error);
		}
	}

	private UUID register(IssuedTokens session, String token) {
		UUID id = UUID.randomUUID();
		installations.register(session.userId(), family(session),
			new RegisterPushRequest(id, PushPlatform.ANDROID, token));
		return id;
	}

	@Test
	@DisplayName("새 로그인은 이전 세션을 모두 끝내고 그 푸시 연결을 비우며 다른 회원은 건드리지 않는다")
	void newLoginEndsPreviousSessionsAndConnections() {
		IssuedTokens old = login(newUser());
		IssuedTokens foreign = login(newUser());
		UUID oldId = register(old, "synthetic-old-device");
		UUID foreignId = register(foreign, "synthetic-foreign");
		time.set(NOW.plusSeconds(1));
		IssuedTokens current = login(old.userId());
		assertUnbound(oldId);
		assertThat(sessions.isSessionActive(family(old), old.userId())).isFalse();
		assertThat(sessions.isSessionActive(family(current), old.userId())).isTrue();
		assertThat(row(foreignId)).containsEntry("user_id", foreign.userId());
		assertThat(sessions.isSessionActive(family(foreign), foreign.userId())).isTrue();
	}

	private void assertUnbound(UUID id) {
		assertThat(row(id)).containsEntry("user_id", null).containsEntry("login_family_id", null)
			.containsEntry("binding_id", null).containsEntry("linked_at", null);
	}

	private Map<String, Object> row(UUID id) {
		return jdbc.queryForMap("select * from push_installation where client_installation_id=?", id);
	}
}
