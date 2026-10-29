package com.e106.tingle.settings.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.KeyringReadRepository;
import com.e106.tingle.keyring.KeyringStatus;
import com.e106.tingle.preference.AgePreferenceMode;
import com.e106.tingle.preference.PreferenceMemberAccess;
import com.e106.tingle.preference.PreferencesService;
import com.e106.tingle.preference.dto.PreferencesRequest;
import com.e106.tingle.settings.SettingsService;
import com.e106.tingle.settings.dto.SettingsRequest;
import com.e106.tingle.user.Gender;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.ProfileAnalysisApplicationService;
import com.e106.tingle.user.ProfileService;
import com.e106.tingle.user.UnderAgeWithdrawalService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 저장 서비스 호출로 변경·설정 세대·당시 입력이 함께 커밋되거나 롤백되는지 검증한다. */
@SpringBootTest(properties = "app.chat-notices.scheduling-enabled=false")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SettingsChangeIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-10-29T02:00:00Z");
	private static final String PRIVATE_INTRODUCTION = "private fixture introduction";
	private static final String PRIVATE_PHOTO = "https://fixture.invalid/private-photo.jpg";

	@Autowired
	private SettingsService settings;
	@Autowired
	private PreferencesService preferences;
	@Autowired
	private ProfileService profiles;
	@Autowired
	private ProfileAnalysisApplicationService analyses;
	@Autowired
	private SettingsChangeCoordinator changes;
	@Autowired
	private UnderAgeWithdrawalService withdrawals;
	@Autowired
	private MemberAccessRepository access;
	@Autowired
	private KeyringReadRepository keyrings;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JsonMapper mapper;
	@Autowired
	private PlatformTransactionManager transactions;
	@MockitoBean
	private OnboardingService onboarding;
	@MockitoBean
	private PreferenceMemberAccess members;
	@MockitoBean
	private Clock clock;

	private long userId;

	@BeforeEach
	void prepare() {
		given(clock.instant()).willReturn(NOW);
		given(clock.getZone()).willReturn(ZoneId.of("Asia/Seoul"));
		given(clock.withZone(any(ZoneId.class))).willReturn(clock);
		userId = newUser();
		jdbc.update("insert into user_setting(user_id,vibration_enabled,led_enabled,settings_version) "
			+ "values (?,false,true,4)", userId);
		jdbc.update("insert into matching_preference(user_id,matching_enabled,age_preference_mode,min_age,"
			+ "max_age,photo_required) values (?,true,true,25,35,false)", userId);
		jdbc.update("insert into profile(user_id,nickname,birth_date,gender,smoking_status,drinking_frequency,"
			+ "introduction,photo_url,completed_at) values (?,?,'1998-01-01','MALE','NON_SMOKER','NEVER',?,?,?)",
			userId, "s101" + userId, PRIVATE_INTRODUCTION, PRIVATE_PHOTO, Timestamp.from(NOW.minusSeconds(1)));
		given(onboarding.canAccess(userId, OnboardingStatus.PREFERENCES_REQUIRED)).willReturn(true);
		given(members.requireGender(userId)).willReturn(Gender.MALE);
	}

	@Test
	void optionSaveCapturesTheChangedOptionsWithoutCopyingPrivateProfileText() {
		var saved = settings.save(userId, userId, new SettingsRequest(false, true), "\"4\"");
		assertThat(saved.body().settingsVersion()).isEqualTo(5);
		assertThat(currentVersion()).isEqualTo(5);
		assertThat(snapshotCount()).isEqualTo(1);
		assertThat(snapshot(5).toString()).doesNotContain(PRIVATE_INTRODUCTION, PRIVATE_PHOTO);
		assertThat(snapshot(5).path("settings").path("version").asLong()).isEqualTo(5);
		assertThat(snapshot(5).path("settings").path("vibrationEnabled").asBoolean()).isFalse();
		assertThat(snapshot(5).path("settings").path("ledEnabled").asBoolean()).isTrue();
		assertThat(snapshot(5).path("preferences").path("matchingEnabled").asBoolean()).isFalse();
		assertThat(snapshot(5).path("preferences").path("requirePartnerPhoto").asBoolean()).isTrue();
		assertThat(snapshot(5).path("profile").path("photoPresent").asBoolean()).isTrue();
		assertThat(jdbc.queryForObject("select matching_enabled from matching_preference where user_id=?",
			Boolean.class, userId)).isFalse();
		assertThat(jdbc.queryForObject("select photo_required from matching_preference where user_id=?",
			Boolean.class, userId)).isTrue();
	}

	@Test
	void preferenceSaveCapturesTheNewAgeRangeInTheSameGeneration() {
		var saved = preferences.save(userId, userId,
			new PreferencesRequest(AgePreferenceMode.RANGE, 30, 40), "\"4\"");
		assertThat(saved.etag()).isEqualTo("\"5\"");
		assertThat(currentVersion()).isEqualTo(5);
		assertThat(snapshotCount()).isEqualTo(1);
		assertThat(snapshot(5).toString()).doesNotContain(PRIVATE_INTRODUCTION, PRIVATE_PHOTO);
		assertThat(snapshot(5).path("preferences").path("desiredAgeMode").asString()).isEqualTo("RANGE");
		assertThat(snapshot(5).path("preferences").path("desiredAgeMin").asInt()).isEqualTo(30);
		assertThat(snapshot(5).path("preferences").path("desiredAgeMax").asInt()).isEqualTo(40);
		assertThat(jdbc.queryForObject("select min_age from matching_preference where user_id=?",
			Integer.class, userId)).isEqualTo(30);
		assertThat(jdbc.queryForObject("select max_age from matching_preference where user_id=?",
			Integer.class, userId)).isEqualTo(40);
	}

	@Test
	void sameValueSuccessfulPutsStillCreateOneSnapshotPerNewVersionAndOldEtagIsStale() {
		SettingsRequest same = new SettingsRequest(true, false);
		settings.save(userId, userId, same, "\"4\"");
		settings.save(userId, userId, same, "\"5\"");
		assertThatThrownBy(() -> settings.save(userId, userId, same, "\"4\""))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRECONDITION_FAILED));
		assertThat(currentVersion()).isEqualTo(6);
		assertThat(snapshotCount()).isEqualTo(2);
		assertThat(snapshot(5)).isNotNull();
		assertThat(snapshot(6)).isNotNull();
	}

	@Test
	void allowedSettingSaveWithMissingProfileKeepsExplicitIncompleteEvidence() {
		jdbc.update("delete from profile where user_id=?", userId);
		settings.save(userId, userId, new SettingsRequest(false, true), "\"4\"");
		assertThat(currentVersion()).isEqualTo(5);
		assertThat(snapshot(5).path("evidenceState").asString()).isEqualTo("INCOMPLETE");
	}

	@Test
	void absentAnalysisIsIncompleteRatherThanAnInventedReadyResult() {
		settings.save(userId, userId, new SettingsRequest(false, true), "\"4\"");
		assertThat(snapshot(5).path("evidenceState").asString()).isEqualTo("INCOMPLETE");
		assertThat(snapshot(5).path("analysis").isNull()).isTrue();
		assertThat(jdbc.queryForObject("select active_personality_id from profile where user_id=?",
			Long.class, userId)).isNull();
	}

	@Test
	void activeAnalysisOfAnotherUserRollsBackOptionsVersionAndSnapshotTogether() {
		long foreignResult = analysis(newUser(), "SUCCEEDED");
		jdbc.update("update profile set active_personality_id=? where user_id=?", foreignResult, userId);
		assertThatThrownBy(() -> settings.save(userId, userId, new SettingsRequest(false, true), "\"4\""))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
		assertThat(currentVersion()).isEqualTo(4);
		assertThat(snapshotCount()).isZero();
		assertThat(jdbc.queryForObject("select matching_enabled from matching_preference where user_id=?",
			Boolean.class, userId)).isTrue();
		assertThat(jdbc.queryForObject("select photo_required from matching_preference where user_id=?",
			Boolean.class, userId)).isFalse();
	}

	@Test
	void nonSuccessfulActiveAnalysisRollsBackPreferenceMutation() {
		long pendingResult = analysis(userId, "PENDING");
		jdbc.update("update profile set active_personality_id=? where user_id=?", pendingResult, userId);
		assertThatThrownBy(() -> preferences.save(userId, userId,
			new PreferencesRequest(AgePreferenceMode.RANGE, 30, 40), "\"4\""))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
		assertThat(currentVersion()).isEqualTo(4);
		assertThat(snapshotCount()).isZero();
		assertThat(jdbc.queryForObject("select min_age from matching_preference where user_id=?",
			Integer.class, userId)).isEqualTo(25);
	}

	@Test
	void actualProfileChangeAdvancesOnceAndTheSamePatchDoesNotAdvanceAgain() {
		JsonNode change = mapper.readTree("{\"introduction\":\"updated private introduction\"}");
		profiles.patch(userId, change);
		assertThat(currentVersion()).isEqualTo(5);
		assertThat(snapshotCount()).isEqualTo(1);
		profiles.patch(userId, change);
		assertThat(currentVersion()).isEqualTo(5);
		assertThat(snapshotCount()).isEqualTo(1);
		assertThat(snapshot(5).toString()).doesNotContain("updated private introduction");
	}

	@Test
	void profileRegistrationAndLostResponseRetryAdvanceOnlyOnce() {
		jdbc.update("update profile set completed_at=null where user_id=?", userId);
		profiles.register(userId);
		assertThat(currentVersion()).isEqualTo(5);
		assertThat(snapshotCount()).isEqualTo(1);
		profiles.register(userId);
		assertThat(currentVersion()).isEqualTo(5);
		assertThat(snapshotCount()).isEqualTo(1);
	}

	@Test
	void earlyProfileChangesDoNotProvisionMissingSettingsRows() {
		jdbc.update("delete from user_setting where user_id=?", userId);
		profiles.patch(userId, mapper.readTree("{\"introduction\":\"early profile change\"}"));
		assertThat(jdbc.queryForObject("select count(*) from user_setting where user_id=?",
			Long.class, userId)).isZero();
		assertThat(snapshotCount()).isZero();
		assertThat(jdbc.queryForObject("select introduction from profile where user_id=?",
			String.class, userId)).isEqualTo("early profile change");
	}

	@Test
	void captureFailureRollsBackTheActualProfileChange() {
		long foreignResult = analysis(newUser(), "SUCCEEDED");
		jdbc.update("update profile set active_personality_id=? where user_id=?", foreignResult, userId);
		assertThatThrownBy(() -> profiles.patch(userId,
			mapper.readTree("{\"introduction\":\"must roll back\"}")))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
		assertThat(jdbc.queryForObject("select introduction from profile where user_id=?",
			String.class, userId)).isEqualTo(PRIVATE_INTRODUCTION);
		assertThat(currentVersion()).isEqualTo(4);
		assertThat(snapshotCount()).isZero();
	}

	@Test
	void outerRollbackRevertsAnOtherwiseSuccessfulSettingAndInputCapture() {
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			settings.save(userId, userId, new SettingsRequest(false, true), "\"4\"");
			transaction.setRollbackOnly();
		});
		assertThat(currentVersion()).isEqualTo(4);
		assertThat(snapshotCount()).isZero();
		assertThat(jdbc.queryForObject("select matching_enabled from matching_preference where user_id=?",
			Boolean.class, userId)).isTrue();
	}

	@Test
	void ageReferenceRefreshAdvancesOnceAfterTheSeoulDateChanges() {
		assertThat(changes.refreshAgeReference(userId)).isFalse();
		assertThat(currentVersion()).isEqualTo(4);
		assertThat(snapshot(4).path("ageReferenceDate").asString()).isEqualTo("2026-10-29");
		assertThat(changes.refreshAgeReference(userId)).isFalse();
		given(clock.instant()).willReturn(NOW.plusSeconds(86400));
		assertThat(changes.refreshAgeReference(userId)).isTrue();
		assertThat(currentVersion()).isEqualTo(5);
		assertThat(snapshot(5).path("ageReferenceDate").asString()).isEqualTo("2026-10-30");
		assertThat(changes.refreshAgeReference(userId)).isFalse();
		assertThat(currentVersion()).isEqualTo(5);
		assertThat(snapshotCount()).isEqualTo(2);
	}

	@Test
	void serverVersionChangePreservesTheOldDeviceAckAndShowsSyncPending() {
		long keyringId = jdbc.queryForObject("insert into keyring(serial_number,firmware_version,owner_user_id,"
			+ "state,synced_settings_version,synced_at) values (?,'fixture',?,'REGISTERED',4,?) returning id",
			Long.class, "settings101-" + UUID.randomUUID(), userId, Timestamp.from(NOW));
		assertThat(access.settingsVersions(userId).acknowledged()).isEqualTo(4);
		settings.save(userId, userId, new SettingsRequest(false, true), "\"4\"");
		assertThat(access.settingsVersions(userId).required()).isEqualTo(5);
		assertThat(access.settingsVersions(userId).acknowledged()).isEqualTo(4);
		assertThat(keyrings.findOwned(userId).getFirst().status()).isEqualTo(KeyringStatus.SYNC_PENDING);
		assertThat(jdbc.queryForObject("select synced_settings_version from keyring where id=?",
			Long.class, keyringId)).isEqualTo(4);
		assertThat(jdbc.queryForObject("select synced_at from keyring where id=?", Timestamp.class, keyringId))
			.isEqualTo(Timestamp.from(NOW));
	}

	@Test
	void competingOptionAndPreferenceUpdatesCaptureOnlyTheWinningGeneration() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> options = workers.submit(() -> saveAfter(start, false));
			Future<Boolean> age = workers.submit(() -> saveAfter(start, true));
			start.countDown();
			assertThat(List.of(options.get(15, TimeUnit.SECONDS), age.get(15, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(true, false);
		}
		assertThat(currentVersion()).isEqualTo(5);
		assertThat(snapshotCount()).isEqualTo(1);
		assertThat(snapshot(5)).isNotNull();
	}

	@Test
	void withdrawalWaitsForInputCaptureThenDeletesTheCommittedSnapshot() throws Exception {
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch started = new CountDownLatch(1);
		AtomicInteger withdrawalPid = new AtomicInteger();
		try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
			Future<?> withdrawal = workers.submit(() -> {
				if (!locked.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Settings lock timed out");
				}
				new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
					withdrawalPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
					started.countDown();
					withdrawals.withdraw(userId);
				});
				return null;
			});
			new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
				int capturePid = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
				jdbc.queryForObject("select user_id from user_setting where user_id=? for update",
					Long.class, userId);
				locked.countDown();
				awaitWithdrawalBlocked(started, withdrawalPid, capturePid);
				changes.captureCurrent(userId);
				assertThat(snapshotCount()).isEqualTo(1);
			});
			withdrawal.get(15, TimeUnit.SECONDS);
		}
		assertThat(snapshotCount()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from users where id=?", Long.class, userId)).isZero();
	}

	@Test
	void applyingTheLatestSuccessfulAnalysisCapturesItsPointerOnceAndReplayIsANoOp() {
		long old = completedAnalysis(userId, "SUCCEEDED", NOW.minusSeconds(7200), "{\"score\":1}");
		long candidate = completedAnalysis(userId, "SUCCEEDED", NOW.minusSeconds(3600), "{\"score\":2}");
		jdbc.update("update profile set active_personality_id=? where user_id=?", old, userId);
		assertThat(analyses.apply(userId, candidate, old)).isTrue();
		assertThat(activeResult()).isEqualTo(candidate);
		assertThat(currentVersion()).isEqualTo(5);
		assertThat(snapshotCount()).isEqualTo(1);
		assertThat(snapshot(5).path("analysis").path("sourceResultId").asLong()).isEqualTo(candidate);
		assertThat(snapshot(5).path("evidenceState").asString()).isEqualTo("REVIEW_REQUIRED");
		assertThat(analyses.apply(userId, candidate, old)).isFalse();
		assertThat(activeResult()).isEqualTo(candidate);
		assertThat(currentVersion()).isEqualTo(5);
		assertThat(snapshotCount()).isEqualTo(1);
	}

	@Test
	void staleExpectedPointerOlderResultForeignOwnerAndFailedResultCannotReplaceTheActiveAnalysis() {
		long old = completedAnalysis(userId, "SUCCEEDED", NOW.minusSeconds(10800), "{\"score\":1}");
		long late = completedAnalysis(userId, "SUCCEEDED", NOW.minusSeconds(7200), "{\"score\":2}");
		long latest = completedAnalysis(userId, "SUCCEEDED", NOW.minusSeconds(3600), "{\"score\":3}");
		jdbc.update("update profile set active_personality_id=? where user_id=?", old, userId);
		assertApplicationError(latest, late, ErrorCode.PRECONDITION_FAILED);
		assertThat(activeResult()).isEqualTo(old);
		assertThat(currentVersion()).isEqualTo(4);
		assertThat(analyses.apply(userId, latest, old)).isTrue();
		assertApplicationError(late, old, ErrorCode.PRECONDITION_FAILED);
		assertApplicationError(late, latest, ErrorCode.PRECONDITION_FAILED);
		long foreign = completedAnalysis(newUser(), "SUCCEEDED", NOW, "{\"score\":4}");
		assertApplicationError(foreign, latest, ErrorCode.INVALID_REQUEST);
		long failed = completedAnalysis(userId, "FAILED", NOW, "{\"score\":5}");
		assertApplicationError(failed, latest, ErrorCode.INVALID_REQUEST);
		assertThat(activeResult()).isEqualTo(latest);
		assertThat(currentVersion()).isEqualTo(5);
		assertThat(snapshotCount()).isEqualTo(1);
	}

	@Test
	void analysisApplicationRollsBackThePointerAndVersionWhenInputCaptureFails() {
		long old = completedAnalysis(userId, "SUCCEEDED", NOW.minusSeconds(7200), "{\"score\":1}");
		long candidate = completedAnalysis(userId, "SUCCEEDED", NOW.minusSeconds(3600), "{\"score\":2}");
		jdbc.update("update profile set active_personality_id=? where user_id=?", old, userId);
		jdbc.update("update matching_preference set min_age=18 where user_id=?", userId);
		assertApplicationError(candidate, old, ErrorCode.SERVICE_UNAVAILABLE);
		assertThat(activeResult()).isEqualTo(old);
		assertThat(currentVersion()).isEqualTo(4);
		assertThat(snapshotCount()).isZero();
	}

	private void assertApplicationError(long resultId, Long expected, ErrorCode code) {
		assertThatThrownBy(() -> analyses.apply(userId, resultId, expected))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
	}

	private void awaitWithdrawalBlocked(CountDownLatch started, AtomicInteger withdrawalPid, int capturePid) {
		try {
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() < deadline) {
				if (Boolean.TRUE.equals(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",
					Boolean.class, capturePid, withdrawalPid.get()))) {
					return;
				}
				TimeUnit.MILLISECONDS.sleep(10);
			}
			throw new AssertionError("Withdrawal did not wait for the settings lock");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Withdrawal wait interrupted", exception);
		}
	}

	private Long activeResult() {
		return jdbc.queryForObject("select active_personality_id from profile where user_id=?", Long.class, userId);
	}

	private boolean saveAfter(CountDownLatch start, boolean age) throws InterruptedException {
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Start latch timed out");
		}
		try {
			if (age) {
				preferences.save(userId, userId, new PreferencesRequest(AgePreferenceMode.RANGE, 30, 40), "\"4\"");
			} else {
				settings.save(userId, userId, new SettingsRequest(false, true), "\"4\"");
			}
			return true;
		} catch (BusinessException exception) {
			if (exception.getErrorCode() != ErrorCode.PRECONDITION_FAILED) {
				throw exception;
			}
			return false;
		}
	}

	private long newUser() {
		return jdbc.queryForObject("insert into users(provider,provider_user_id,status,created_at,is_operator) "
			+ "values ('KAKAO',?,'ACTIVE',?,false) returning id", Long.class,
			"settings101-" + UUID.randomUUID(), Timestamp.from(NOW));
	}

	private long analysis(long ownerId, String status) {
		return completedAnalysis(ownerId, status, NOW, "{}");
	}

	private long completedAnalysis(long ownerId, String status, Instant completedAt, String result) {
		long surveyId = jdbc.queryForObject("insert into survey(user_id,survey_version,revision,completed_at) "
			+ "values (?,'SIS-Q-1.1-100',0,?) returning id", Long.class, ownerId, Timestamp.from(completedAt));
		return jdbc.queryForObject("insert into personality(survey_id,analysis_version,status,result) "
			+ "values (?,'test-analysis',cast(? as analysis_status),cast(? as jsonb)) returning id",
			Long.class, surveyId, status, result);
	}

	private long currentVersion() {
		return jdbc.queryForObject("select settings_version from user_setting where user_id=?", Long.class, userId);
	}

	private long snapshotCount() {
		return jdbc.queryForObject("select count(*) from settings_snapshot where user_id=?", Long.class, userId);
	}

	private JsonNode snapshot(long version) {
		String input = jdbc.queryForObject("select input_json from settings_snapshot "
			+ "where user_id=? and settings_version=?", String.class, userId, version);
		return mapper.readTree(input);
	}
}
