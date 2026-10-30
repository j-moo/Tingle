package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.e106.tingle.encounter.EncounterReceipts.FrozenEvidence;
import com.e106.tingle.encounter.EncounterReceipts.StoredReceipt;
import com.e106.tingle.encounter.dto.EncounterSyncRequest;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.DeviceProof;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;
import com.e106.tingle.encounter.dto.EncounterSyncResponse.Receipt;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = EncounterReceiptsPersistenceTest.Config.class, properties = {
	"spring.main.web-application-type=none", "spring.profiles.active=test",
	"spring.jpa.hibernate.ddl-auto=none", "spring.docker.compose.enabled=false",
	"spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:database/schema.sql"
})
class EncounterReceiptsPersistenceTest {
	private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
	private static final String HASH = "a".repeat(64);

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@Import({EncounterReceipts.class, EncounterSyncService.class, EncounterAccessRepository.class})
	static class Config {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer("postgres:17");
		}
	}

	@Autowired
	private EncounterReceipts receipts;
	@Autowired
	private EncounterSyncService sync;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JsonMapper mapper;
	@Autowired
	private PlatformTransactionManager transactions;

	private long actor;
	private long peer;
	private long matching;
	private UUID submission;
	private UUID event;

	@BeforeEach
	void prepareParticipant() {
		actor = user();
		peer = user();
		matching = matching(actor, peer);
		submission = UUID.randomUUID();
		event = UUID.randomUUID();
	}

	@Test
	void canonicalRequestIgnoresObjectOrderButKeepsArrayOrderAndIdentity() {
		Map<String, Object> first = new LinkedHashMap<>();
		first.put("submissionId", submission);
		first.put("evidence", Map.of("count", 1, "protocol", "v1"));
		Map<String, Object> second = new LinkedHashMap<>();
		second.put("evidence", Map.of("protocol", "v1", "count", 1.0));
		second.put("submissionId", submission);
		assertThat(receipts.canonicalHash(first)).isEqualTo(receipts.canonicalHash(second));
		assertThat(receipts.canonicalHash(Map.of("ids", new int[] {1, 2})))
			.isNotEqualTo(receipts.canonicalHash(Map.of("ids", new int[] {2, 1})));
		second.put("submissionId", UUID.randomUUID());
		assertThat(receipts.canonicalHash(first)).isNotEqualTo(receipts.canonicalHash(second));
	}

	@Test
	void requiresCallingTransaction() {
		assertThatThrownBy(() -> receipts.find(actor, 1, submission, event, HASH))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void restoresFirstResultFromFreshReaderAfterRawFieldsAreCleared() {
		StoredReceipt first = receipt();
		assertThat(inTransaction(() -> save(first))).isEqualTo(first);
		jdbc.update("update matching_participant set encounter_signature=null,encounter_reported_at=null,"
			+ "encounter_verified_at=null where matching_id=?", matching);
		jdbc.update("update matching set met_at=null,raw_delete_at=null where id=?", matching);
		EncounterReceipts freshReader = new EncounterReceipts(jdbc, mapper);
		assertThat(inTransaction(() -> freshReader.find(actor, 1, submission, event, HASH))).contains(first);
		assertThat(inTransaction(() -> save(receipt()))).isEqualTo(first);
	}

	@Test
	void concurrentRetriesPublishOnlyOneOriginalReceipt() throws Exception {
		StoredReceipt first = receipt();
		StoredReceipt second = receipt();
		CountDownLatch start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var left = pool.submit(() -> {
				assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
				return inTransaction(() -> save(first));
			});
			var right = pool.submit(() -> {
				assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
				return inTransaction(() -> save(second));
			});
			start.countDown();
			StoredReceipt saved = left.get(15, TimeUnit.SECONDS);
			assertThat(right.get(15, TimeUnit.SECONDS)).isEqualTo(saved);
			assertThat(saved).isIn(first, second);
			assertThat(inTransaction(() -> receipts.find(actor, 1, submission, event, HASH))).contains(saved);
		}
	}

	@Test
	void conflictsOnChangedBodyOrNewSubmissionForSameEvent() {
		inTransaction(() -> save(receipt()));
		assertConflict(() -> inTransaction(() -> receipts.find(actor, 1, submission, event, "b".repeat(64))));
		assertConflict(() -> inTransaction(() -> receipts.find(actor, 1, UUID.randomUUID(), event, HASH)));
		assertConflict(() -> inTransaction(() -> receipts.find(actor, 1, submission, UUID.randomUUID(), HASH)));
	}

	@Test
	void isolatesUsersAndDevicesWithoutLeakingAnotherReceipt() {
		inTransaction(() -> save(receipt()));
		assertThat(inTransaction(() -> receipts.find(peer, 1, submission, event, HASH))).isEmpty();
		assertThat(inTransaction(() -> receipts.find(actor, 2, submission, event, HASH))).isEmpty();
	}

	@Test
	void preservesDecisionAndSafetyNamespacesAndEarlierEncounterGenerations() {
		jdbc.update("update matching_participant set request_state=cast(? as jsonb) where matching_id=? and user_id=?",
			"{\"command\":\"ACCEPT\",\"receiptVersion\":\"1\",\"safety:old\":\"preserved\","
				+ "\"encounter:old\":\"preserved\"}", matching, actor);
		inTransaction(() -> save(receipt()));
		assertThat(jdbc.queryForObject("select request_state->>'command' from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, matching, actor)).isEqualTo("ACCEPT");
		assertThat(jdbc.queryForObject("select request_state->>'safety:old' from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, matching, actor)).isEqualTo("preserved");
		assertThat(jdbc.queryForObject("select request_state->>'encounter:old' from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, matching, actor)).isEqualTo("preserved");
	}

	@Test
	void rollsBackReceiptWithCallerAndRefusesMissingParticipant() {
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			save(receipt());
			transaction.setRollbackOnly();
		});
		assertThat(inTransaction(() -> receipts.find(actor, 1, submission, event, HASH))).isEmpty();
		jdbc.update("delete from matching_participant where matching_id=? and user_id=?", matching, actor);
		assertUnavailable(() -> inTransaction(() -> save(receipt())));
	}

	@Test
	void refusesCorruptAndAmbiguousStoredAliases() {
		inTransaction(() -> save(receipt()));
		long duplicate = matching(actor, user());
		jdbc.update("update matching_participant set request_state=(select request_state from matching_participant "
			+ "where matching_id=? and user_id=?) where matching_id=? and user_id=?",
			matching, actor, duplicate, actor);
		assertUnavailable(() -> inTransaction(() -> receipts.find(actor, 1, submission, event, HASH)));
		jdbc.update("delete from matching_participant where matching_id=?", duplicate);
		jdbc.update("update matching_participant set request_state=jsonb_set(request_state,cast(? as text[]),"
			+ "to_jsonb('broken'::text)) where matching_id=? and user_id=?",
			"{encounter:submission:1:" + submission + "}", matching, actor);
		assertUnavailable(() -> inTransaction(() -> receipts.find(actor, 1, submission, event, HASH)));
	}

	@Test
	void rejectsRawEvidenceFieldsAndIncompleteEvidenceBeforeSaving() {
		StoredReceipt original = receipt();
		StoredReceipt unsafe = new StoredReceipt(original.receiptId(), NOW, original.generationId(),
			original.evidence(),
			original.responseJson().replace("{", "{\"peerCredential\":\"private\","));
		assertThatThrownBy(() -> inTransaction(() -> save(unsafe)))
			.isInstanceOf(InvalidDataAccessApiUsageException.class)
			.hasRootCauseInstanceOf(IllegalArgumentException.class);
		FrozenEvidence invalid = new FrozenEvidence(null, 1, UUID.randomUUID(), HASH, HASH, "v1", "v1");
		StoredReceipt unverified = new StoredReceipt(original.receiptId(), NOW, original.generationId(), invalid,
			original.responseJson());
		assertThatThrownBy(() -> inTransaction(() -> save(unverified)))
			.isInstanceOf(InvalidDataAccessApiUsageException.class)
			.hasRootCauseInstanceOf(IllegalArgumentException.class);
		assertThat(inTransaction(() -> receipts.find(actor, 1, submission, event, HASH))).isEmpty();
	}

	@Test
	void rejectsMissingNullableFieldsInvalidDeadlineAndWrongResponseTypes() {
		StoredReceipt original = receipt();
		String[] malformed = {
			original.responseJson().replace("\"errorCode\":null,", "").replace(",\"errorCode\":null", ""),
			original.responseJson().replace("\"counterpartDeadlineAt\":null", "\"counterpartDeadlineAt\":\"invalid\""),
			original.responseJson().replace("\"retryable\":false", "\"retryable\":\"false\""),
			original.responseJson().replace("\"encounterStatus\":\"WAITING\"", "\"encounterStatus\":\"UNKNOWN\""),
			original.responseJson().replace("\"errorCode\":null", "\"errorCode\":\"POLICY_UNRESOLVED\"")
		};
		for (String response : malformed) {
			StoredReceipt invalid = new StoredReceipt(original.receiptId(), NOW, original.generationId(),
				original.evidence(), response);
			assertThatThrownBy(() -> inTransaction(() -> save(invalid)))
				.isInstanceOf(InvalidDataAccessApiUsageException.class)
				.hasRootCauseInstanceOf(IllegalArgumentException.class);
		}
		assertThat(inTransaction(() -> receipts.find(actor, 1, submission, event, HASH))).isEmpty();
	}

	@Test
	void syncRecoversFirstReceiptWithoutChangingStatusDeadlineOrStoredState() {
		long device = registeredDevice();
		Submission original = submitted();
		StoredReceipt saved = saveForSync(device, original);
		String before = requestState();
		jdbc.update("update matching set status='EXPIRED' where id=?", matching);
		Receipt expected = mapper.readValue(saved.responseJson(), Receipt.class);
		var request = new EncounterSyncRequest(device, List.of(original));
		assertThat(sync.sync(actor, request).results()).containsExactly(expected);
		// 새 서비스 인스턴스도 메모리 캐시가 아닌 같은 영속 영수증을 복구한다.
		var restarted = new EncounterSyncService(new EncounterAccessRepository(jdbc), receipts, mapper);
		assertThat(inTransaction(() -> restarted.sync(actor, request)).results()).containsExactly(expected);
		assertThat(requestState()).isEqualTo(before);
		assertThat(jdbc.queryForObject("select status::text from matching where id=?", String.class, matching))
			.isEqualTo("EXPIRED");
	}

	@Test
	void syncPreservesMixedBatchOrderAndDoesNotCreateAReceiptForNewUnverifiedSubmission() {
		long device = registeredDevice();
		Submission original = submitted();
		StoredReceipt saved = saveForSync(device, original);
		Submission unknown = new Submission(UUID.randomUUID(), UUID.randomUUID(), NOW, "v1", "v1",
			UUID.randomUUID(), "synthetic-peer", new DeviceProof("synthetic", "unverified"));
		String before = requestState();
		assertThat(sync.sync(actor, new EncounterSyncRequest(device, List.of(unknown, original))).results())
			.containsExactly(Receipt.unresolved(unknown), mapper.readValue(saved.responseJson(), Receipt.class));
		assertThat(requestState()).isEqualTo(before);
	}

	@Test
	void syncRejectsChangedProofAndChangedSubmissionForStoredEvent() {
		long device = registeredDevice();
		Submission original = submitted();
		saveForSync(device, original);
		String before = requestState();
		Submission changed = new Submission(submission, event, NOW, "v1", "v1", original.settingsSnapshotId(),
			original.peerCredential(), new DeviceProof("synthetic", "changed"));
		assertConflict(() -> sync.sync(actor, new EncounterSyncRequest(device, List.of(changed))));
		Submission alias = new Submission(UUID.randomUUID(), event, NOW, "v1", "v1", original.settingsSnapshotId(),
			original.peerCredential(), original.evidence());
		assertConflict(() -> sync.sync(actor, new EncounterSyncRequest(device, List.of(alias))));
		assertThat(requestState()).isEqualTo(before);
	}

	@Test
	void syncCannotReplayAnotherDeviceOrAfterOwnershipTransfer() {
		long device = registeredDevice();
		Submission original = submitted();
		saveForSync(device, original);
		long otherDevice = registeredDevice();
		assertThat(sync.sync(actor, new EncounterSyncRequest(otherDevice, List.of(original))).results())
			.containsExactly(Receipt.unresolved(original));
		jdbc.update("update keyring set owner_user_id=? where id=?", peer, device);
		assertThatThrownBy(() -> sync.sync(actor, new EncounterSyncRequest(device, List.of(original))))
			.isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
		jdbc.update("update users set signup_completed_at=? where id=?", Timestamp.from(NOW), peer);
		assertThat(sync.sync(peer, new EncounterSyncRequest(device, List.of(original))).results())
			.containsExactly(Receipt.unresolved(original));
	}

	@Test
	void syncRejectsCorruptedStoredResponseInsteadOfReturningDeletePermission() {
		long device = registeredDevice();
		Submission original = submitted();
		saveForSync(device, original);
		String key = "encounter:submission:" + device + ":" + submission;
		jdbc.update("update matching_participant set request_state=jsonb_set(request_state,array[?],"
			+ "to_jsonb('corrupt'::text)) where matching_id=? and user_id=?", key, matching, actor);
		assertUnavailable(() -> sync.sync(actor, new EncounterSyncRequest(device, List.of(original))));
	}

	private long registeredDevice() {
		jdbc.update("update users set signup_completed_at=? where id=?", Timestamp.from(NOW), actor);
		return jdbc.queryForObject("insert into keyring(serial_number,firmware_version,owner_user_id,state) "
			+ "values (?,'synthetic',?,'REGISTERED') returning id", Long.class, UUID.randomUUID().toString(), actor);
	}

	private Submission submitted() {
		return new Submission(submission, event, NOW, "v1", "v1", UUID.randomUUID(),
			"synthetic-peer", new DeviceProof("synthetic", "unverified"));
	}

	/** 공식 서명 검증을 대체하지 않는 저장 완료 fixture. 공개 API로 신규 접수하지 않는다. */
	private StoredReceipt saveForSync(long device, Submission original) {
		StoredReceipt fixture = receipt();
		Receipt response = new Receipt(submission, event, "STORED", fixture.receiptId(), true, "WAITING",
			NOW.plusSeconds(60), false, null);
		StoredReceipt saved = new StoredReceipt(fixture.receiptId(), fixture.receivedAt(), fixture.generationId(),
			fixture.evidence(), mapper.writeValueAsString(response));
		return inTransaction(() -> receipts.save(matching, actor, device, submission, event,
			receipts.canonicalHash(original), saved));
	}

	private String requestState() {
		return jdbc.queryForObject("select request_state::text from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, matching, actor);
	}

	private StoredReceipt save(StoredReceipt receipt) {
		return receipts.save(matching, actor, 1, submission, event, HASH, receipt);
	}

	private StoredReceipt receipt() {
		UUID id = UUID.randomUUID();
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("submissionId", submission);
		response.put("eventId", event);
		response.put("receiptId", id);
		response.put("receiptStatus", "STORED");
		response.put("canDeleteLocalRecord", true);
		response.put("encounterStatus", "WAITING");
		response.put("counterpartDeadlineAt", null);
		response.put("retryable", false);
		response.put("errorCode", null);
		return new StoredReceipt(id, NOW, UUID.randomUUID(),
			new FrozenEvidence(UUID.randomUUID(), 1, UUID.randomUUID(), HASH, HASH, "v1", "v1"),
			mapper.writeValueAsString(response));
	}

	private <T> T inTransaction(Supplier<T> work) {
		return new TransactionTemplate(transactions).execute(transaction -> work.get());
	}

	private static void assertConflict(Runnable work) {
		assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
	}

	private static void assertUnavailable(Runnable work) {
		assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
	}

	private long user() {
		return jdbc.queryForObject("insert into users(provider,provider_user_id,status,created_at) "
			+ "values ('GOOGLE',?,'ACTIVE',?) returning id", Long.class, UUID.randomUUID().toString(),
			Timestamp.from(NOW));
	}

	private long matching(long first, long second) {
		long id = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,status) values (?,?,'WAITING') "
			+ "returning id", Long.class, Math.min(first, second), Math.max(first, second));
		jdbc.update("insert into matching_participant(matching_id,user_id) values (?,?),(?,?)", id, first, id, second);
		return id;
	}
}
