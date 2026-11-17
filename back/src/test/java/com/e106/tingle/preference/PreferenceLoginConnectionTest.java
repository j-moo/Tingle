package com.e106.tingle.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.e106.tingle.SharedPostgres;
import com.e106.tingle.agreement.AgreementService;
import com.e106.tingle.auth.AuthService;
import com.e106.tingle.auth.SocialVerifier;
import com.e106.tingle.auth.TokenService;
import com.e106.tingle.auth.dto.LoginRequest;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.global.error.GlobalExceptionHandler;
import com.e106.tingle.settings.UserSetting;
import com.e106.tingle.settings.snapshot.SettingsChangeCoordinator;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.PendingEmails;
import com.e106.tingle.user.User;
import com.e106.tingle.withdrawal.WithdrawalIdentityService;

/**
 * 실제 로그인 서비스·회원 JPA·초기 행 생성·희망 조건 MVC 연결. 외부 소셜·토큰·A 단계만 대체한다.
 */
@SpringBootTest(classes = PreferenceLoginConnectionTest.Config.class, properties = {
	"spring.jpa.hibernate.ddl-auto=create-drop", "spring.docker.compose.enabled=false",
	"spring.profile.active=test", "spring.jpa.open-in-view=false"
})
class PreferenceLoginConnectionTest {

	@MockitoBean
	private SettingsChangeCoordinator settingsChanges;

	private static final Instant NOW = Instant.parse("2026-10-28T03:00:00Z");

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = {MatchingPreference.class, UserSetting.class, User.class,
		WithdrawalIdentityService.class})
	@EnableJpaRepositories(basePackages = {"com.e106.tingle.preference", "com.e106.tingle.user"})
	@Import({AuthService.class, PendingEmails.class, PreferenceProvisioningService.class,
		PreferenceProvisioningAccess.class,
		PreferencesService.class, PreferenceMemberAccess.class, WithdrawalIdentityService.class})
	static class Config {

		@Bean
		JdbcConnectionDetails postgresConnection() {
			return SharedPostgres.newDatabase();
		}

		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
		}
	}

	@Autowired
	private AuthService auth;
	@Autowired
	private PreferencesService preferences;
	@Autowired
	private JdbcTemplate jdbc;
	@MockitoBean
	private SocialVerifier socials;
	@MockitoBean
	private TokenService tokens;
	@MockitoBean
	private AgreementService agreements;
	@MockitoBean
	private OnboardingService onboarding;
	private MockMvc mvc;

	@BeforeEach
	void prepare() {
		jdbc.update("delete from profile");
		jdbc.update("delete from matching_preference");
		jdbc.update("delete from user_setting");
		jdbc.update("delete from users");
		given(socials.verify(any(), any())).willReturn(new SocialVerifier.SocialAccount("member-70", null));
		given(onboarding.statusOf(anyLong())).willReturn(OnboardingStatus.AGREEMENT_REQUIRED);
		given(onboarding.canAccess(anyLong(), any())).willReturn(true);
		given(tokens.issue(anyLong())).willAnswer(invocation -> {
			long userId = invocation.getArgument(0);
			assertThat(jdbc.queryForObject("select count(*) from user_setting where user_id=?", Long.class, userId))
				.isEqualTo(1);
			assertThat(jdbc.queryForObject("select count(*) from matching_preference where user_id=?",
				Long.class, userId)).isEqualTo(1);
			return new TokenService.IssuedTokens(userId, UUID.randomUUID(), "test-access", NOW.plusSeconds(1800),
				"test-refresh", NOW.plusSeconds(3600));
		});
		mvc = MockMvcBuilders.standaloneSetup(new PreferencesController(preferences, onboarding))
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.setControllerAdvice(new GlobalExceptionHandler()).build();
	}

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void loginInitializesBeforeIssuingTokensThenPreferencesCanBeReadAndSaved() throws Exception {
		assertThat(auth.login("kakao", new LoginRequest("test-social", null, null, null, null)).onboardingStatus())
			.isEqualTo(OnboardingStatus.AGREEMENT_REQUIRED);
		long userId = loggedInUser();
		prepareRegisteredProfile(userId);
		mvc.perform(get("/api/users/{id}/preferences", userId)).andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"0\""))
			.andExpect(jsonPath("$.desiredGenders[0]").value("FEMALE"));
		mvc.perform(put("/api/users/{id}/preferences", userId).contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"0\"")
			.content("{\"desiredAgeMode\":\"ANY\",\"desiredAgeMin\":null,\"desiredAgeMax\":null}"))
			.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
		assertThat(preferences.get(userId, userId).body().desiredAgeMode()).isEqualTo(AgePreferenceMode.ANY);
	}

	@Test
	void reloginPreservesSavedChoicesVersionAndOtherSettings() {
		auth.login("kakao", new LoginRequest("test-social", null, null, null, null));
		long userId = loggedInUser();
		prepareRegisteredProfile(userId);
		jdbc.update("update user_setting set vibration_enabled=false,led_enabled=false,settings_version=8");
		jdbc.update("update matching_preference set age_preference_mode=true,min_age=30,photo_required=true");
		auth.login("kakao", new LoginRequest("test-social-again", null, null, null, null));
		assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(1);
		assertThat(preferences.get(userId, userId).etag()).isEqualTo("\"8\"");
		assertThat(preferences.get(userId, userId).body().desiredAgeMin()).isEqualTo(30);
		assertThat(jdbc.queryForObject("select photo_required from matching_preference", Boolean.class)).isTrue();
		assertThat(jdbc.queryForObject("select led_enabled from user_setting", Boolean.class)).isFalse();
	}

	@Test
	void inactiveMemberCannotBeInitializedOrIssuedTokens() {
		jdbc.update("insert into users(provider,provider_user_id,status,created_at,is_operator) "
			+ "values ('KAKAO','member-70','SUSPENDED',?,false)", java.sql.Timestamp.from(NOW));
		assertThatThrownBy(() -> auth.login("kakao", new LoginRequest("test-social", null, null, null, null)))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
		verifyNoInteractions(tokens);
		assertThat(jdbc.queryForObject("select count(*) from user_setting", Long.class)).isZero();
	}

	@Test
	void reloginCannotResetMissingSettingOrIssueTokens() {
		auth.login("kakao", new LoginRequest("test-social", null, null, null, null));
		long userId = loggedInUser();
		jdbc.update("update matching_preference set age_preference_mode=true,min_age=30,photo_required=true");
		jdbc.update("delete from user_setting");
		clearInvocations(tokens);
		assertServiceUnavailableLogin();
		verifyNoInteractions(tokens);
		assertThat(jdbc.queryForObject("select count(*) from user_setting", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select min_age from matching_preference where user_id=?",
			Integer.class, userId)).isEqualTo(30);
	}

	@Test
	void completedLegacyMemberWithNoErd21RowsCannotBeTreatedAsFreshSignup() {
		jdbc.update("insert into users(provider,provider_user_id,status,created_at,signup_completed_at,is_operator) "
			+ "values ('KAKAO','member-70','ACTIVE',?,?,false)",
			java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
		assertServiceUnavailableLogin();
		verifyNoInteractions(tokens);
		assertThat(jdbc.queryForObject("select count(*) from user_setting", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from matching_preference", Long.class)).isZero();
	}

	private void assertServiceUnavailableLogin() {
		assertThatThrownBy(() -> auth.login("kakao", new LoginRequest("test-social", null, null, null, null)))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
	}

	@Test
	void initializationFailureDoesNotIssueTokensOrLeavePartialSettings() {
		jdbc.execute("alter table matching_preference add constraint test_fail_initialization check (user_id<0)");
		try {
			assertThatThrownBy(() -> auth.login("kakao", new LoginRequest("test-social", null, null, null, null)))
				.isInstanceOf(DataIntegrityViolationException.class);
			verifyNoInteractions(tokens);
			assertThat(jdbc.queryForObject("select count(*) from user_setting", Long.class)).isZero();
		} finally {
			jdbc.execute("alter table matching_preference drop constraint test_fail_initialization");
		}
	}

	private long loggedInUser() {
		return jdbc.queryForObject("select id from users where provider_user_id='member-70'", Long.class);
	}

	private void prepareRegisteredProfile(long userId) {
		jdbc.update("insert into profile(user_id,gender,completed_at) values (?,'MALE',?)",
			userId, java.sql.Timestamp.from(NOW));
		Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "none").subject(String.valueOf(userId)).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
	}
}
