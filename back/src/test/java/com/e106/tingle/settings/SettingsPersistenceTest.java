package com.e106.tingle.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.preference.AgePreferenceMode;
import com.e106.tingle.preference.MatchingPreference;
import com.e106.tingle.preference.MatchingPreferenceRepository;
import com.e106.tingle.preference.PreferenceMemberAccess;
import com.e106.tingle.preference.PreferencesService;
import com.e106.tingle.preference.dto.PreferencesRequest;
import com.e106.tingle.settings.dto.SettingsRequest;
import com.e106.tingle.settings.snapshot.SettingsChangeCoordinator;
import com.e106.tingle.user.Gender;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.OnboardingStatus;

/** 실제 PostgreSQL 잠금·롤백·설정과 희망 조건 사이의 공용 버전 경쟁을 검증한다. */
@SpringBootTest(classes = SettingsPersistenceTest.Config.class, properties = {
	"spring.jpa.hibernate.ddl-auto=create-drop", "spring.docker.compose.enabled=false",
	"spring.profiles.active=test", "spring.jpa.open-in-view=false"
})
class SettingsPersistenceTest {

	@MockitoBean
	private SettingsChangeCoordinator settingsChanges;

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = {MatchingPreference.class, UserSetting.class})
	@EnableJpaRepositories(basePackageClasses = MatchingPreferenceRepository.class)
	@Import({SettingsService.class, PreferencesService.class})
	static class Config {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer("postgres:17");
		}
	}

	@Autowired
	private SettingsService service;
	@Autowired
	private PreferencesService preferences;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private PlatformTransactionManager transactions;
	@MockitoBean
	private OnboardingService onboarding;
	@MockitoBean
	private PreferenceMemberAccess members;

	@BeforeEach
	void prepare() {
		jdbc.update("delete from matching_preference");
		jdbc.update("delete from user_setting");
		jdbc.update("insert into matching_preference "
			+ "(user_id,matching_enabled,age_preference_mode,min_age,max_age,photo_required) "
			+ "values (7,true,true,25,35,false)");
		jdbc.update("insert into user_setting "
			+ "(user_id,vibration_enabled,led_enabled,settings_version) values (7,false,true,4)");
		given(onboarding.canAccess(7, OnboardingStatus.PREFERENCES_REQUIRED)).willReturn(true);
		given(members.requireGender(7)).willReturn(Gender.MALE);
	}

	@Test
	void savesOnlyOptionsAndAdvancesTheSharedVersionOnce() {
		assertThat(service.get(7, 7).etag()).isEqualTo("\"4\"");
		var result = service.save(7, 7, new SettingsRequest(false, true), "\"4\"");
		assertThat(result.etag()).isEqualTo("\"5\"");
		assertThat(result.body().settingsVersion()).isEqualTo(5);
		assertThat(service.get(7, 7).body().matchingEnabled()).isFalse();
		assertThat(service.get(7, 7).body().requirePartnerPhoto()).isTrue();
		assertThat(jdbc.queryForObject("select min_age from matching_preference", Integer.class)).isEqualTo(25);
		assertThat(jdbc.queryForObject("select max_age from matching_preference", Integer.class)).isEqualTo(35);
		assertThat(jdbc.queryForObject("select age_preference_mode from matching_preference", Boolean.class)).isTrue();
		assertThat(jdbc.queryForObject("select vibration_enabled from user_setting", Boolean.class)).isFalse();
		assertThat(jdbc.queryForObject("select led_enabled from user_setting", Boolean.class)).isTrue();
	}

	@Test
	void eachSuccessfulPutAdvancesVersionAndLostResponseRetryIsStale() {
		SettingsRequest same = new SettingsRequest(true, false);
		service.save(7, 7, same, "\"4\"");
		assertError(() -> service.save(7, 7, same, "\"4\""), ErrorCode.PRECONDITION_FAILED);
		assertThat(service.save(7, 7, same, "\"5\"").body().settingsVersion()).isEqualTo(6);
	}

	@Test
	void missingStaleWeakWildcardAndMultipleEtagsCannotWrite() {
		assertError(() -> service.save(7, 7, new SettingsRequest(false, true), null),
			ErrorCode.PRECONDITION_REQUIRED);
		for (String etag : List.of("\"3\"", "W/\"4\"", "*", "4", "\"4\", \"3\"")) {
			assertError(() -> service.save(7, 7, new SettingsRequest(false, true), etag),
				ErrorCode.PRECONDITION_FAILED);
		}
		assertThat(service.get(7, 7).body().settingsVersion()).isEqualTo(4);
		assertThat(service.get(7, 7).body().matchingEnabled()).isTrue();
	}

	@Test
	void anotherUserIsRejectedBeforeOnboardingOrPersistenceAccess() {
		assertError(() -> service.get(8, 7), ErrorCode.FORBIDDEN);
		assertError(() -> service.save(8, 7, new SettingsRequest(false, true), "\"4\""), ErrorCode.FORBIDDEN);
		verifyNoInteractions(onboarding);
	}

	@Test
	void incompleteOnboardingCannotReadOrWrite() {
		given(onboarding.canAccess(7, OnboardingStatus.PREFERENCES_REQUIRED)).willReturn(false);
		assertError(() -> service.get(7, 7), ErrorCode.ONBOARDING_REQUIRED);
		assertError(() -> service.save(7, 7, new SettingsRequest(false, true), "\"4\""),
			ErrorCode.ONBOARDING_REQUIRED);
		assertThat(jdbc.queryForObject("select settings_version from user_setting", Long.class)).isEqualTo(4);
	}

	@Test
	void missingPreferenceNeverRecreatesDefaultsOrAdvancesVersion() {
		jdbc.update("delete from matching_preference");
		assertError(() -> service.get(7, 7), ErrorCode.SERVICE_UNAVAILABLE);
		assertError(() -> service.save(7, 7, new SettingsRequest(false, true), "\"4\""),
			ErrorCode.SERVICE_UNAVAILABLE);
		assertThat(jdbc.queryForObject("select count(*) from matching_preference", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select settings_version from user_setting", Long.class)).isEqualTo(4);
	}

	@Test
	void missingVersionNeverRecreatesAZeroGeneration() {
		jdbc.update("delete from user_setting");
		assertError(() -> service.get(7, 7), ErrorCode.SERVICE_UNAVAILABLE);
		assertError(() -> service.save(7, 7, new SettingsRequest(false, true), "\"4\""),
			ErrorCode.SERVICE_UNAVAILABLE);
		assertThat(jdbc.queryForObject("select count(*) from user_setting", Long.class)).isZero();
	}

	@Test
	void failedTransactionRollsBackBothFlagsAndVersion() {
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			service.save(7, 7, new SettingsRequest(false, true), "\"4\"");
			transaction.setRollbackOnly();
		});
		assertThat(service.get(7, 7).body().settingsVersion()).isEqualTo(4);
		assertThat(service.get(7, 7).body().matchingEnabled()).isTrue();
		assertThat(service.get(7, 7).body().requirePartnerPhoto()).isFalse();
	}

	@Test
	void settingsAndPreferencesCompeteForTheSameGeneration() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> options = workers.submit(() -> saveAfter(start, false));
			Future<Boolean> age = workers.submit(() -> saveAfter(start, true));
			start.countDown();
			assertThat(List.of(options.get(10, TimeUnit.SECONDS), age.get(10, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(true, false);
		}
		assertThat(service.get(7, 7).body().settingsVersion()).isEqualTo(5);
		boolean optionsWon = !service.get(7, 7).body().matchingEnabled();
		assertThat(jdbc.queryForObject("select min_age from matching_preference", Integer.class))
			.isEqualTo(optionsWon ? 25 : 30);
		assertThat(service.get(7, 7).body().requirePartnerPhoto()).isEqualTo(optionsWon);
	}

	private boolean saveAfter(CountDownLatch start, boolean age) throws Exception {
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("concurrent settings test did not start");
		}
		try {
			if (age) {
				preferences.save(7, 7, new PreferencesRequest(AgePreferenceMode.RANGE, 30, 40), "\"4\"");
			} else {
				service.save(7, 7, new SettingsRequest(false, true), "\"4\"");
			}
			return true;
		} catch (BusinessException exception) {
			assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
			return false;
		}
	}

	private static void assertError(Runnable call, ErrorCode expected) {
		assertThatThrownBy(call::run).isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode()).isEqualTo(expected);
	}
}
