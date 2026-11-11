package com.e106.tingle.settings.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.SharedPostgres;
import com.e106.tingle.preference.MatchingPreference;
import com.e106.tingle.preference.PreferenceUserSettingRepository;
import com.e106.tingle.settings.UserSetting;

/** 실제 PostgreSQL에서 불변 입력의 유일성·경합·호출자 트랜잭션 참여를 검증한다. */
@SpringBootTest(classes = SettingsSnapshotPersistenceTest.Config.class, properties = {
	"spring.jpa.hibernate.ddl-auto=create-drop", "spring.docker.compose.enabled=false",
	"spring.profiles.active=test", "spring.jpa.open-in-view=false"
})
class SettingsSnapshotPersistenceTest {

	private static final Instant CAPTURED_AT = Instant.parse("2026-10-29T02:00:00.123456Z");
	private static final String FORMAT = "test-input-v1";
	private static final String INPUT = "{\"ready\":false,\"source\":{\"id\":17}}";

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = {SettingsSnapshot.class, UserSetting.class, MatchingPreference.class})
	@EnableJpaRepositories(basePackageClasses = {
		SettingsSnapshotRepository.class, PreferenceUserSettingRepository.class
	})
	@Import({SettingsSnapshotService.class, SettingsSnapshotJson.class})
	static class Config {

		@Bean
		JdbcConnectionDetails postgresConnection() {
			return SharedPostgres.newDatabase();
		}

		@Bean
		Clock clock() {
			return Clock.fixed(CAPTURED_AT.plusNanos(789), ZoneOffset.UTC);
		}
	}

	@Autowired
	private SettingsSnapshotService service;
	@Autowired
	private PreferenceUserSettingRepository settings;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private PlatformTransactionManager transactions;

	@BeforeEach
	void prepare() {
		jdbc.update("delete from settings_snapshot");
		jdbc.update("delete from user_setting");
		jdbc.update("insert into user_setting "
			+ "(user_id,vibration_enabled,led_enabled,settings_version) values (7,false,true,4)");
	}

	@Test
	void requiresTheCallerTransactionForCaptureAndFind() {
		assertThatThrownBy(() -> service.capture(7, 4, FORMAT, INPUT))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(() -> service.find(7, 4)).isInstanceOf(IllegalTransactionStateException.class);
		assertThat(snapshotCount()).isZero();
	}

	@Test
	void storesCanonicalInputAndHashWithoutAdvancingTheSettingsVersion() throws Exception {
		String raw = "{\"right\":{\"y\":2,\"x\":1}, \"left\":[true,null]}";
		String canonical = "{\"left\":[true,null],\"right\":{\"x\":1,\"y\":2}}";
		SettingsSnapshotView saved = capture(4, FORMAT, raw);
		assertThat(saved.userId()).isEqualTo(7);
		assertThat(saved.settingsVersion()).isEqualTo(4);
		assertThat(saved.inputFormat()).isEqualTo(FORMAT);
		assertThat(saved.inputJson()).isEqualTo(canonical);
		assertThat(saved.inputHash()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(canonical.getBytes(StandardCharsets.UTF_8))));
		assertThat(saved.capturedAt()).isEqualTo(CAPTURED_AT);
		assertThat(find(4)).contains(saved);
		assertThat(currentVersion()).isEqualTo(4);
		assertThat(snapshotCount()).isEqualTo(1);
	}

	@Test
	void equivalentReplayKeepsTheOriginalRowAndCaptureEvidence() {
		SettingsSnapshotView first = capture(4, FORMAT, INPUT);
		Long firstId = jdbc.queryForObject("select id from settings_snapshot", Long.class);
		SettingsSnapshotView replay = capture(4, FORMAT, "{ \"source\": {\"id\":17}, \"ready\":false }");
		assertThat(replay).isEqualTo(first);
		assertThat(jdbc.queryForObject("select id from settings_snapshot", Long.class)).isEqualTo(firstId);
		assertThat(snapshotCount()).isEqualTo(1);
		assertThat(currentVersion()).isEqualTo(4);
	}

	@Test
	void historicalReplayAndQueryDoNotUseTheNewCurrentInput() {
		SettingsSnapshotView historical = capture(4, FORMAT, INPUT);
		jdbc.update("update user_setting set settings_version=5 where user_id=7");
		SettingsSnapshotView current = capture(5, FORMAT, "{\"source\":{\"id\":18},\"ready\":false}");
		assertThat(capture(4, FORMAT, INPUT)).isEqualTo(historical);
		assertThat(find(4)).contains(historical);
		assertThat(find(5)).contains(current);
		assertThat(find(3)).isEmpty();
		assertThat(currentVersion()).isEqualTo(5);
		assertThat(snapshotCount()).isEqualTo(2);
	}

	@Test
	void differentContentOrFormatCannotReplaceTheSameGeneration() {
		SettingsSnapshotView first = capture(4, FORMAT, INPUT);
		assertThatThrownBy(() -> capture(4, FORMAT, "{\"ready\":true}"))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> capture(4, "test-input-v2", INPUT))
			.isInstanceOf(IllegalStateException.class);
		assertThat(find(4)).contains(first);
		assertThat(snapshotCount()).isEqualTo(1);
		assertThat(currentVersion()).isEqualTo(4);
	}

	@Test
	void refusesNewHistoricalOrFutureGenerationAndMissingSettings() {
		assertThatThrownBy(() -> capture(3, FORMAT, INPUT)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> capture(5, FORMAT, INPUT)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new TransactionTemplate(transactions)
			.execute(transaction -> service.capture(8, 4, FORMAT, INPUT)))
			.isInstanceOf(IllegalStateException.class);
		assertThat(snapshotCount()).isZero();
		assertThat(currentVersion()).isEqualTo(4);
	}

	@Test
	void keepsInitialGenerationZeroAsEvidenceWithoutClaimingMatchingReadiness() {
		jdbc.update("update user_setting set settings_version=0 where user_id=7");
		SettingsSnapshotView saved = capture(0, FORMAT, "{\"state\":\"INCOMPLETE\"}");
		assertThat(saved.settingsVersion()).isZero();
		assertThat(saved.inputJson()).isEqualTo("{\"state\":\"INCOMPLETE\"}");
		assertThat(currentVersion()).isZero();
	}

	@Test
	void callerRollbackRevertsBothTheVersionAndCapturedInput() {
		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			UserSetting setting = settings.findForUpdate(7).orElseThrow();
			setting.advanceVersion();
			service.capture(7, 5, FORMAT, INPUT);
			throw new IllegalStateException("caller aborted");
		})).isInstanceOf(IllegalStateException.class).hasMessage("caller aborted");
		assertThat(currentVersion()).isEqualTo(4);
		assertThat(snapshotCount()).isZero();
		assertThat(find(5)).isEmpty();
	}

	@Test
	void callerCanAdvanceAndCaptureInTheSameCommit() {
		SettingsSnapshotView saved = new TransactionTemplate(transactions).execute(transaction -> {
			settings.findForUpdate(7).orElseThrow().advanceVersion();
			return service.capture(7, 5, FORMAT, INPUT);
		});
		assertThat(currentVersion()).isEqualTo(5);
		assertThat(find(5)).contains(saved);
		assertThat(snapshotCount()).isEqualTo(1);
	}

	@Test
	void malformedCaptureRevertsTheCallerVersionIncrease() {
		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			settings.findForUpdate(7).orElseThrow().advanceVersion();
			service.capture(7, 5, FORMAT, "{\"duplicate\":1,\"duplicate\":2}");
		})).isInstanceOf(IllegalArgumentException.class);
		assertThat(currentVersion()).isEqualTo(4);
		assertThat(snapshotCount()).isZero();
	}

	@Test
	void databaseRejectsDuplicateUserAndVersionEvenOutsideTheService() {
		capture(4, FORMAT, INPUT);
		assertThatThrownBy(() -> jdbc.update("insert into settings_snapshot "
			+ "(user_id,settings_version,input_format,input_json,input_hash,captured_at) "
			+ "select user_id,settings_version,input_format,input_json,input_hash,captured_at "
			+ "from settings_snapshot where user_id=7 and settings_version=4"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(snapshotCount()).isEqualTo(1);
	}

	@Test
	void concurrentEquivalentCaptureReturnsOneImmutableRow() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<SettingsSnapshotView> first = workers.submit(() -> captureAfter(start, INPUT));
			Future<SettingsSnapshotView> second = workers.submit(() -> captureAfter(start,
				"{\"source\":{\"id\":17},\"ready\":false}"));
			start.countDown();
			assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
		}
		assertThat(snapshotCount()).isEqualTo(1);
		assertThat(currentVersion()).isEqualTo(4);
	}

	@Test
	void concurrentDifferentCaptureHasOneWinnerWithoutOverwritingIt() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		String alternative = "{\"ready\":false,\"source\":{\"id\":18}}";
		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = workers.submit(() -> tryCaptureAfter(start, INPUT));
			Future<Boolean> second = workers.submit(() -> tryCaptureAfter(start, alternative));
			start.countDown();
			assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(true, false);
		}
		assertThat(find(4).orElseThrow().inputJson()).isIn(INPUT, alternative);
		assertThat(snapshotCount()).isEqualTo(1);
		assertThat(currentVersion()).isEqualTo(4);
	}

	private SettingsSnapshotView captureAfter(CountDownLatch start, String input) throws Exception {
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("capture race did not start");
		}
		return capture(4, FORMAT, input);
	}

	private boolean tryCaptureAfter(CountDownLatch start, String input) throws Exception {
		try {
			captureAfter(start, input);
			return true;
		} catch (IllegalStateException exception) {
			assertThat(exception).hasMessageContaining("conflicts with frozen generation");
			return false;
		}
	}

	private SettingsSnapshotView capture(long version, String format, String input) {
		return new TransactionTemplate(transactions)
			.execute(transaction -> service.capture(7, version, format, input));
	}

	private Optional<SettingsSnapshotView> find(long version) {
		return new TransactionTemplate(transactions).execute(transaction -> service.find(7, version));
	}

	private long snapshotCount() {
		return jdbc.queryForObject("select count(*) from settings_snapshot", Long.class);
	}

	private long currentVersion() {
		return jdbc.queryForObject("select settings_version from user_setting where user_id=7", Long.class);
	}
}
