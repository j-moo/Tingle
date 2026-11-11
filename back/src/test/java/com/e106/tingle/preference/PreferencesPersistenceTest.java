package com.e106.tingle.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
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
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.SharedPostgres;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.preference.PreferencesService.VersionedPreferences;
import com.e106.tingle.preference.dto.PreferencesRequest;
import com.e106.tingle.settings.UserSetting;
import com.e106.tingle.settings.snapshot.SettingsChangeCoordinator;
import com.e106.tingle.user.Gender;

/**
 * A151의 구 회원 엔티티를 대체하지 않고 이 도메인 실제 JPA·PostgreSQL 트랜잭션을 검증한다.
 */
@SpringBootTest(classes = PreferencesPersistenceTest.Config.class, properties = {
	"spring.jpa.hibernate.ddl-auto=create-drop", "spring.docker.compose.enabled=false",
	"spring.profiles.active=test", "spring.jpa.open-in-view=false"
})
class PreferencesPersistenceTest {

	@MockitoBean
	private SettingsChangeCoordinator settingsChanges;

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = {MatchingPreference.class, UserSetting.class})
	@EnableJpaRepositories(basePackageClasses = MatchingPreferenceRepository.class)
	@Import({PreferencesService.class, PreferenceProvisioningService.class})
	static class Config {

		@Bean
		JdbcConnectionDetails postgresConnection() {
			return SharedPostgres.newDatabase();
		}
	}

	@Autowired
	private PreferencesService service;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@MockitoBean
	private PreferenceMemberAccess members;
	@MockitoBean
	private PreferenceProvisioningAccess provisioningAccess;
	@Autowired
	private PreferenceProvisioningService provisioning;

	@BeforeEach
	void prepare() {
		jdbc.update("delete from matching_preference");
		jdbc.update("delete from user_setting");
		jdbc.update("insert into matching_preference "
			+ "(user_id,matching_enabled,age_preference_mode,min_age,max_age,photo_required) "
			+ "values (7,true,null,null,null,true)");
		jdbc.update("insert into user_setting "
			+ "(user_id,vibration_enabled,led_enabled,settings_version) values (7,false,true,4)");
		given(members.requireGender(7)).willReturn(Gender.MALE);
		// 이 격리 컨텍스트에는 회원 테이블이 없으므로 회원 행 잠금 포트만 PG 트랜잭션 잠금으로 대체한다.
		given(provisioningAccess.lockActiveMember(anyLong())).willAnswer(invocation -> {
			long userId = invocation.getArgument(0);
			jdbc.execute("select pg_advisory_xact_lock(" + userId + ")");
			return false;
		});
	}

	@Test
	void unknownAgeIsNotAnyAndCounterpartGenderIsDerived() {
		VersionedPreferences result = service.get(7, 7);
		assertThat(result.etag()).isEqualTo("\"4\"");
		assertThat(result.body().desiredAgeMode()).isNull();
		assertThat(result.body().desiredGenders()).containsExactly(Gender.FEMALE);
		given(members.requireGender(7)).willReturn(Gender.FEMALE);
		assertThat(service.get(7, 7).body().desiredGenders()).containsExactly(Gender.MALE);
	}

	@Test
	void savesAgeAndVersionOnceWithoutChangingOtherSettings() {
		VersionedPreferences result = service.save(7, 7, range(25, null), "\"4\"");
		assertThat(result.etag()).isEqualTo("\"5\"");
		assertThat(service.get(7, 7).body().desiredAgeMin()).isEqualTo(25);
		assertThat(jdbc.queryForObject("select photo_required from matching_preference", Boolean.class)).isTrue();
		assertThat(jdbc.queryForObject("select matching_enabled from matching_preference", Boolean.class)).isTrue();
		assertThat(jdbc.queryForObject("select vibration_enabled from user_setting", Boolean.class)).isFalse();
		assertThat(jdbc.queryForObject("select led_enabled from user_setting", Boolean.class)).isTrue();
	}

	@Test
	void savesExplicitAnyWithNullBounds() {
		service.save(7, 7, new PreferencesRequest(AgePreferenceMode.ANY, null, null), "\"4\"");
		assertThat(jdbc.queryForObject("select age_preference_mode from matching_preference", Boolean.class)).isFalse();
		assertThat(service.get(7, 7).body().desiredAgeMode()).isEqualTo(AgePreferenceMode.ANY);
		assertThat(service.get(7, 7).body().desiredAgeMin()).isNull();
		assertThat(service.get(7, 7).body().desiredAgeMax()).isNull();
	}

	@Test
	void staleMissingWeakAndWildcardEtagsCannotWrite() {
		assertError(() -> service.save(7, 7, range(20, 30), null), ErrorCode.PRECONDITION_REQUIRED);
		for (String etag : List.of("\"3\"", "W/\"4\"", "*", "4")) {
			assertError(() -> service.save(7, 7, range(20, 30), etag), ErrorCode.PRECONDITION_FAILED);
		}
		assertThat(service.get(7, 7).etag()).isEqualTo("\"4\"");
		assertThat(service.get(7, 7).body().desiredAgeMode()).isNull();
	}

	@Test
	void sharedSettingChangeInvalidatesOldPreferencesEtag() {
		String before = service.get(7, 7).etag();
		jdbc.update("update user_setting set led_enabled=false,settings_version=settings_version+1 where user_id=7");
		assertError(() -> service.save(7, 7, range(20, 30), before), ErrorCode.PRECONDITION_FAILED);
		assertThat(service.get(7, 7).etag()).isEqualTo("\"5\"");
	}

	@Test
	void transactionRollbackRestoresAgeAndVersion() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.executeWithoutResult(status -> {
			service.save(7, 7, range(20, 30), "\"4\"");
			status.setRollbackOnly();
		});
		assertThat(service.get(7, 7).etag()).isEqualTo("\"4\"");
		assertThat(service.get(7, 7).body().desiredAgeMode()).isNull();
	}

	@Test
	void concurrentRequestsWithSameEtagHaveOneWinner() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = workers.submit(() -> saveAfter(start, 20));
			Future<Boolean> second = workers.submit(() -> saveAfter(start, 30));
			start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(true, false);
		}
		assertThat(service.get(7, 7).etag()).isEqualTo("\"5\"");
		assertThat(service.get(7, 7).body().desiredAgeMin()).isIn(20, 30);
	}

	@Test
	void anotherUserIsRejectedBeforeMemberOrDataAccess() {
		assertError(() -> service.get(8, 7), ErrorCode.FORBIDDEN);
		assertError(() -> service.save(8, 7, range(20, 30), "\"4\""), ErrorCode.FORBIDDEN);
		verifyNoInteractions(members);
	}

	@Test
	void failedOnboardingCannotReadOrChangePreferences() {
		given(members.requireGender(7)).willThrow(new BusinessException(ErrorCode.ONBOARDING_REQUIRED));
		assertError(() -> service.get(7, 7), ErrorCode.ONBOARDING_REQUIRED);
		assertError(() -> service.save(7, 7, range(20, 30), "\"4\""), ErrorCode.ONBOARDING_REQUIRED);
		assertThat(jdbc.queryForObject("select settings_version from user_setting", Long.class)).isEqualTo(4);
		assertThat(jdbc.queryForObject("select age_preference_mode from matching_preference", Boolean.class)).isNull();
	}

	@Test
	void initialRowsKeepUnselectedAgeAndDefaultSettings() {
		provisioning.initializeForMember(8);
		assertThat(jdbc.queryForMap("select matching_enabled,age_preference_mode,min_age,max_age,photo_required "
			+ "from matching_preference where user_id=8"))
			.containsEntry("matching_enabled", true).containsEntry("age_preference_mode", null)
			.containsEntry("min_age", null).containsEntry("max_age", null).containsEntry("photo_required", false);
		assertThat(jdbc.queryForMap("select vibration_enabled,led_enabled,settings_version "
			+ "from user_setting where user_id=8"))
			.containsEntry("vibration_enabled", true).containsEntry("led_enabled", true)
			.containsEntry("settings_version", 0L);
	}

	@Test
	void repeatedInitializationNeverResetsExistingUserChoiceOrVersion() {
		service.save(7, 7, range(30, null), "\"4\"");
		provisioning.initializeForMember(7);
		provisioning.initializeForMember(7);
		assertThat(service.get(7, 7).etag()).isEqualTo("\"5\"");
		assertThat(service.get(7, 7).body().desiredAgeMin()).isEqualTo(30);
		assertThat(jdbc.queryForObject("select photo_required from matching_preference where user_id=7",
			Boolean.class)).isTrue();
		assertThat(jdbc.queryForObject("select vibration_enabled from user_setting where user_id=7",
			Boolean.class)).isFalse();
	}

	@Test
	void initializesOnlyMissingHalfWithoutResettingExistingSettings() {
		jdbc.update("delete from matching_preference where user_id=7");
		provisioning.initializeForMember(7);
		assertThat(service.get(7, 7).etag()).isEqualTo("\"4\"");
		assertThat(service.get(7, 7).body().desiredAgeMode()).isNull();
		assertThat(jdbc.queryForObject("select vibration_enabled from user_setting where user_id=7",
			Boolean.class)).isFalse();
	}

	@Test
	void missingSettingCannotResetExistingPreferenceGenerationToZero() {
		jdbc.update("update matching_preference set age_preference_mode=true,min_age=30 where user_id=7");
		jdbc.update("delete from user_setting where user_id=7");
		assertError(() -> provisioning.initializeForMember(7), ErrorCode.SERVICE_UNAVAILABLE);
		assertThat(jdbc.queryForObject("select count(*) from user_setting where user_id=7", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select min_age from matching_preference where user_id=7", Integer.class))
			.isEqualTo(30);
		assertThat(jdbc.queryForObject("select photo_required from matching_preference where user_id=7",
			Boolean.class)).isTrue();
	}

	@Test
	void completedSignupWithBothRowsMissingRequiresExplicitMigration() {
		given(provisioningAccess.lockActiveMember(8)).willReturn(true);
		assertError(() -> provisioning.initializeForMember(8), ErrorCode.SERVICE_UNAVAILABLE);
		assertThat(jdbc.queryForObject("select count(*) from user_setting where user_id=8", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from matching_preference where user_id=8", Long.class))
			.isZero();
	}

	@Test
	void initializationRollbackRestoresBothMissingRows() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.executeWithoutResult(status -> {
			provisioning.initializeForMember(8);
			status.setRollbackOnly();
		});
		assertThat(jdbc.queryForObject("select count(*) from user_setting where user_id=8", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from matching_preference where user_id=8", Long.class))
			.isZero();
	}

	@Test
	void secondInsertFailureRollsBackFirstInsert() {
		jdbc.execute("alter table matching_preference add constraint test_reject_user check (user_id<>8)");
		try {
			assertThatThrownBy(() -> provisioning.initializeForMember(8))
				.isInstanceOf(DataIntegrityViolationException.class);
			assertThat(jdbc.queryForObject("select count(*) from user_setting where user_id=8", Long.class)).isZero();
		} finally {
			jdbc.execute("alter table matching_preference drop constraint test_reject_user");
		}
	}

	@Test
	void failedMemberProofCannotCreateRows() {
		doThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)).when(provisioningAccess).lockActiveMember(8);
		assertError(() -> provisioning.initializeForMember(8), ErrorCode.RESOURCE_NOT_FOUND);
		assertError(() -> provisioning.initializeForMember(0), ErrorCode.INVALID_REQUEST);
		assertThat(jdbc.queryForObject("select count(*) from user_setting where user_id=8", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from matching_preference where user_id=8", Long.class))
			.isZero();
	}

	@Test
	void concurrentInitializationsCreateOnePairOfRows() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<?> first = workers.submit(() -> initializeAfter(start));
			Future<?> second = workers.submit(() -> initializeAfter(start));
			start.countDown();
			first.get(10, TimeUnit.SECONDS);
			second.get(10, TimeUnit.SECONDS);
		}
		assertThat(jdbc.queryForObject("select count(*) from user_setting where user_id=8", Long.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from matching_preference where user_id=8", Long.class))
			.isEqualTo(1);
		assertThat(jdbc.queryForObject("select settings_version from user_setting where user_id=8", Long.class))
			.isZero();
	}

	private void initializeAfter(CountDownLatch start) {
		try {
			if (!start.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("concurrent initialization did not start");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
		provisioning.initializeForMember(8);
	}

	@Test
	void missingProvisionedRowsDoNotCreateFakeDefaults() {
		jdbc.update("delete from matching_preference");
		assertError(() -> service.get(7, 7), ErrorCode.SERVICE_UNAVAILABLE);
		assertError(() -> service.save(7, 7, range(20, 30), "\"4\""), ErrorCode.SERVICE_UNAVAILABLE);
		assertThat(jdbc.queryForObject("select settings_version from user_setting", Long.class)).isEqualTo(4);
	}

	private boolean saveAfter(CountDownLatch start, int minimum) throws Exception {
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("concurrent test did not start");
		}
		try {
			service.save(7, 7, range(minimum, null), "\"4\"");
			return true;
		} catch (BusinessException exception) {
			assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
			return false;
		}
	}

	private static PreferencesRequest range(int min, Integer max) {
		return new PreferencesRequest(AgePreferenceMode.RANGE, min, max);
	}

	private static void assertError(Runnable call, ErrorCode expected) {
		assertThatThrownBy(call::run).isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode()).isEqualTo(expected);
	}
}
