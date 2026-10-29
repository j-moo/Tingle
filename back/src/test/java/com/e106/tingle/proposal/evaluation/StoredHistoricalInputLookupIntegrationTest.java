package com.e106.tingle.proposal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.settings.snapshot.SettingsSnapshotService;
import com.e106.tingle.settings.snapshot.SettingsSnapshotView;

import tools.jackson.databind.json.JsonMapper;

/** 실제 PostgreSQL의 72 저장 행을 검증한다. UUID 진위·fact·scope·공식 릴리스 검증 성공은 아니다. */
@SpringBootTest(properties = "app.chat-notices.scheduling-enabled=false")
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class StoredHistoricalInputLookupIntegrationTest {
	private static final UUID ORIGINAL_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
	private static final String FORMAT = "settings-source-evidence-v1";
	private static final String INPUT = "{\"profile\":{\"introduction\":\"synthetic original\"}}";
	@Autowired
	private StoredHistoricalInputLookup lookup;
	@Autowired
	private SettingsSnapshotService snapshots;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private PlatformTransactionManager transactions;
	@Autowired
	private JsonMapper mapper;
	private long userId;
	private long otherUserId;

	@BeforeEach
	void prepare() {
		userId = jdbc.queryForObject("select coalesce(max(user_id),200000)+100 from user_setting", Long.class);
		otherUserId = userId + 1;
		jdbc.update("insert into user_setting(user_id,vibration_enabled,led_enabled,settings_version) "
			+ "values(?,false,true,4),(?,false,true,4)", userId, otherUserId);
		jdbc.update("insert into profile(user_id,introduction) values(?,'synthetic original')", userId);
	}

	@Test
	void currentProfileAndGenerationChangesKeepTheOriginalStoredInput() {
		SettingsSnapshotView past = capture(userId, 4, FORMAT, INPUT);
		HistoricalEvaluationEvidence.Snapshot reference = reference(past);
		jdbc.update("update profile set introduction='synthetic changed' where user_id=?", userId);
		jdbc.update("update user_setting set settings_version=5 where user_id=?", userId);
		capture(userId, 5, FORMAT, "{\"profile\":{\"introduction\":\"synthetic changed\"}}");

		assertThat(lookup.find(reference, past.capturedAt().plusSeconds(1))).contains(past);
		assertThat(jdbc.queryForObject("select introduction from profile where user_id=?", String.class, userId))
			.isEqualTo("synthetic changed");
		assertThat(jdbc.queryForObject("select count(*) from settings_snapshot where user_id=?", Long.class, userId))
			.isEqualTo(2);
	}

	@Test
	void generationZeroAndTheExactCaptureBoundaryAreAllowed() {
		jdbc.update("update user_setting set settings_version=0 where user_id=?", userId);
		SettingsSnapshotView past = capture(userId, 0, FORMAT, INPUT);

		assertThat(HistoricalEvaluationEvidence.Snapshot.sourceRef(userId, 0))
			.isEqualTo("settings_snapshot:" + userId + ":0");
		assertThat(lookup.find(reference(past), past.capturedAt())).contains(past);
	}

	@Test
	void differentExpectedHashDoesNotSelectAStoredInput() {
		SettingsSnapshotView past = capture(userId, 4, FORMAT, INPUT);
		HistoricalEvaluationEvidence.Snapshot wrong = new HistoricalEvaluationEvidence.Snapshot(userId, ORIGINAL_ID,
			"f".repeat(64), 4L, HistoricalEvaluationEvidence.Snapshot.sourceRef(userId, 4));

		assertThat(lookup.find(wrong, after(past))).isEmpty();
	}

	@Test
	void foreignOwnerSourceReferenceCannotSelectAnotherUsersRow() {
		SettingsSnapshotView own = capture(userId, 4, FORMAT, INPUT);
		SettingsSnapshotView foreign = capture(otherUserId, 4, FORMAT, "{\"profile\":{\"id\":999}}");
		HistoricalEvaluationEvidence.Snapshot wrong = new HistoricalEvaluationEvidence.Snapshot(userId, ORIGINAL_ID,
			foreign.inputHash(), 4L, HistoricalEvaluationEvidence.Snapshot.sourceRef(otherUserId, 4));

		assertThat(lookup.find(wrong, after(foreign))).isEmpty();
		assertThat(lookup.find(reference(own), after(foreign))).contains(own);
	}

	@Test
	void missingHistoricalGenerationNeverFallsBackToCurrentInput() {
		SettingsSnapshotView current = capture(userId, 4, FORMAT, INPUT);
		HistoricalEvaluationEvidence.Snapshot missing = new HistoricalEvaluationEvidence.Snapshot(userId, ORIGINAL_ID,
			current.inputHash(), 3L, HistoricalEvaluationEvidence.Snapshot.sourceRef(userId, 3));

		assertThat(lookup.find(missing, after(current))).isEmpty();
	}

	@Test
	void sourceReferencesMustUseTheExactServerOwnedForm() {
		SettingsSnapshotView past = capture(userId, 4, FORMAT, INPUT);
		HistoricalEvaluationEvidence.Snapshot wrong = new HistoricalEvaluationEvidence.Snapshot(userId, ORIGINAL_ID,
			past.inputHash(), 4L, "settings_snapshot:" + userId + ":04");

		assertThat(lookup.find(wrong, after(past))).isEmpty();
	}

	@Test
	void anotherStoredInputFormatDoesNotProveTheMatchingInput() {
		SettingsSnapshotView past = capture(userId, 4, "synthetic-other-format", INPUT);

		assertThat(lookup.find(reference(past), after(past))).isEmpty();
	}

	@Test
	void laterSnapshotIsNotHistoricalEvidence() {
		SettingsSnapshotView later = capture(userId, 4, FORMAT, INPUT);

		assertThat(lookup.find(reference(later), later.capturedAt().minusNanos(1000))).isEmpty();
	}

	@Test
	void changedStoredBytesCannotReuseTheOriginalHash() {
		SettingsSnapshotView past = capture(userId, 4, FORMAT, INPUT);
		jdbc.update("update settings_snapshot set input_json=? where user_id=? and settings_version=4",
			"{\"profile\":{\"introduction\":\"synthetic tampered\"}}", userId);

		assertThat(lookup.find(reference(past), after(past))).isEmpty();
	}

	@Test
	void agreeingClaimAndStoredHashStillRequireTheOriginalContentHash() {
		SettingsSnapshotView past = capture(userId, 4, FORMAT, INPUT);
		jdbc.update("update settings_snapshot set input_hash=? where user_id=? and settings_version=4",
			"f".repeat(64), userId);
		HistoricalEvaluationEvidence.Snapshot forged = new HistoricalEvaluationEvidence.Snapshot(userId, ORIGINAL_ID,
			"f".repeat(64), 4L, HistoricalEvaluationEvidence.Snapshot.sourceRef(userId, 4));

		assertThat(lookup.find(forged, after(past))).isEmpty();
	}

	@Test
	void legacyAndPartiallyMissingReferencesAreNotBackfilledFromTheCurrentRow() {
		SettingsSnapshotView past = capture(userId, 4, FORMAT, INPUT);
		HistoricalEvaluationEvidence.Snapshot legacy = new HistoricalEvaluationEvidence.Snapshot(userId, ORIGINAL_ID,
			past.inputHash());
		HistoricalEvaluationEvidence.Snapshot missingSource = new HistoricalEvaluationEvidence.Snapshot(
			userId, ORIGINAL_ID, past.inputHash(), 4L, null);
		HistoricalEvaluationEvidence.Snapshot missingGeneration = new HistoricalEvaluationEvidence.Snapshot(
			userId, ORIGINAL_ID, past.inputHash(), null, HistoricalEvaluationEvidence.Snapshot.sourceRef(userId, 4));

		assertThat(lookup.find(legacy, after(past))).isEmpty();
		assertThat(lookup.find(missingSource, after(past))).isEmpty();
		assertThat(lookup.find(missingGeneration, after(past))).isEmpty();
		assertThat(legacy.settingsVersion()).isNull();
		assertThat(legacy.inputSourceRef()).isNull();
		assertThat(lookup.find(null, after(past))).isEmpty();
		assertThat(lookup.find(reference(past), null)).isEmpty();
	}

	@Test
	void legacyJsonKeepsMissingFieldsAndNewMetadataRoundTrips() {
		SettingsSnapshotView past = capture(userId, 4, FORMAT, INPUT);
		String legacyJson = "{\"userId\":" + userId + ",\"snapshotId\":\"" + ORIGINAL_ID
			+ "\",\"inputHash\":\"" + past.inputHash() + "\"}";
		HistoricalEvaluationEvidence.Snapshot legacy = mapper.readValue(
			legacyJson, HistoricalEvaluationEvidence.Snapshot.class);
		HistoricalEvaluationEvidence.Snapshot original = reference(past);
		HistoricalEvaluationEvidence.Snapshot decoded = mapper.readValue(mapper.writeValueAsString(original),
			HistoricalEvaluationEvidence.Snapshot.class);

		assertThat(legacy.settingsVersion()).isNull();
		assertThat(legacy.inputSourceRef()).isNull();
		assertThat(lookup.find(legacy, after(past))).isEmpty();
		assertThat(decoded).isEqualTo(original);
		assertThat(decoded.snapshotId()).isEqualTo(ORIGINAL_ID);
		assertThat(lookup.find(decoded, after(past))).contains(past);
	}

	private SettingsSnapshotView capture(long owner, long version, String format, String input) {
		return new TransactionTemplate(transactions)
			.execute(status -> snapshots.capture(owner, version, format, input));
	}

	private static HistoricalEvaluationEvidence.Snapshot reference(SettingsSnapshotView stored) {
		return new HistoricalEvaluationEvidence.Snapshot(stored.userId(), ORIGINAL_ID, stored.inputHash(),
			stored.settingsVersion(), HistoricalEvaluationEvidence.Snapshot.sourceRef(
				stored.userId(), stored.settingsVersion()));
	}

	private static Instant after(SettingsSnapshotView stored) {
		return stored.capturedAt().plusSeconds(1);
	}
}
