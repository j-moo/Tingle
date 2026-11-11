package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.SharedPostgres;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = ReligionEvidenceRetirementTest.Config.class, properties = {
	"spring.main.web-application-type=none", "spring.profiles.active=test",
	"spring.jpa.hibernate.ddl-auto=none", "spring.docker.compose.enabled=false",
	"spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:database/schema.sql"
})
class ReligionEvidenceRetirementTest {
	private static final String HASH = "a".repeat(64);
	private static final String OUTER_HASH = "b".repeat(64);
	private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@Import({ReligionEvidenceRetirement.class, EncounterReceipts.class})
	static class Config {
		@Bean
		JdbcConnectionDetails postgresConnection() {
			return SharedPostgres.newDatabase();
		}
	}

	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JsonMapper json;
	@Autowired
	private ReligionEvidenceRetirement retirement;
	@Autowired
	private EncounterReceipts receipts;
	@Autowired
	private PlatformTransactionManager transactions;
	private long actor;
	private long peer;
	private long matching;
	private UUID generation;
	private UUID submission;
	private UUID event;

	@BeforeEach
	void prepare() {
		peer = user();
		actor = user(); // 철회 사용자가 높은 ID여도 낮은 ID의 workflow를 지워야 한다.
		generation = UUID.randomUUID();
		submission = UUID.randomUUID();
		event = UUID.randomUUID();
		matching = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,status) "
			+ "values (?,?,'WAITING') returning id", Long.class, peer, actor);
		jdbc.update("insert into matching_participant(matching_id,user_id) values (?,?),(?,?)",
			matching, peer, matching, actor);
		jdbc.update("insert into settings_snapshot(user_id,settings_version,input_format,input_json,input_hash,"
			+ "captured_at,redacted_at,redaction_reason) values (?,1,'test','{}',?,?,?,'PROFILE_RELIGION_WITHDRAWN')",
			actor, "0".repeat(64), Timestamp.from(NOW), Timestamp.from(NOW));
	}

	@Test
	void removesNestedCopiesButKeepsChatAndDuplicateGuards() {
		seedCurrent();
		jdbc.update("update matching set status='ACCEPTED',proposed_at=? where id=?", Timestamp.from(NOW), matching);
		long chat = jdbc.queryForObject("insert into chat(matching_id,created_at,last_message_seq,icebreakers,"
			+ "ai_state,expires_at) values (?,?,0,'{}','{}',?) returning id", Long.class, matching,
			Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(600)));

		retirement.retire(actor);

		assertThat(state(peer)).doesNotContain(HASH, OUTER_HASH, "verified-input", "encounter:workflow")
			.contains("encounter:proposal:", "encounter:event:", "encounter:retired:", "unrelated");
		assertThat(state(actor)).doesNotContain(HASH, OUTER_HASH, "verified-input")
			.contains("encounter:submission:", "encounter:event:");
		assertThat(jdbc.queryForObject("select evaluation::text from matching where id=?", String.class, matching))
			.isNull();
		assertThat(jdbc.queryForObject("select count(*) from matching_participant where matching_id=? "
			+ "and encounter_signature is not null", Integer.class, matching)).isZero();
		assertThat(jdbc.queryForObject("select status::text from matching where id=?", String.class, matching))
			.isEqualTo("ACCEPTED");
		assertThat(jdbc.queryForObject("select id from chat where matching_id=?", Long.class, matching))
			.isEqualTo(chat);
		assertRetired(submission, event);
		assertRetired(UUID.randomUUID(), event);
		assertRetired(submission, UUID.randomUUID());
		String before = state(peer) + state(actor);
		retirement.retire(actor);
		assertThat(state(peer) + state(actor)).isEqualTo(before);
	}

	@Test
	void retiresOldArchiveWithoutTouchingReconsentedCurrentGeneration() {
		UUID old = generation;
		generation = UUID.randomUUID();
		Map<String, Object> current = new LinkedHashMap<>();
		current.put("encounter:intake", encode(Map.of("generation", generation)));
		current.put("encounter:workflow", encode(work(2)));
		current.put("encounter:archive:" + old, Map.of("context", Map.of("generation", old),
			"input", encode(input(1)), "workflow", encode(Map.of("inputHash", OUTER_HASH, "input", input(1)))));
		current.put("encounter:evaluation:" + old, encode(Map.of("inputHash", OUTER_HASH)));
		writeState(peer, current);
		writeState(actor, Map.of("encounter:verified-input", encode(input(2))));
		jdbc.update("update matching set evaluation=cast(? as jsonb) where id=?", encode(input(2)), matching);
		String evaluation = jdbc.queryForObject("select evaluation::text from matching where id=?",
			String.class, matching);
		String actorState = state(actor);

		retirement.retire(actor);

		assertThat(json.readTree(state(peer)).path("encounter:workflow").asString()).isEqualTo(encode(work(2)));
		assertThat(json.readTree(state(peer)).path("encounter:archive:" + old).asString())
			.isEqualTo(ReligionEvidenceRetirement.TOMBSTONE);
		assertThat(json.readTree(state(peer)).path("encounter:evaluation:" + old).asString())
			.isEqualTo(ReligionEvidenceRetirement.TOMBSTONE);
		assertThat(json.readTree(state(actor)).path("encounter:verified-input"))
			.isEqualTo(json.readTree(actorState).path("encounter:verified-input"));
		assertThat(jdbc.queryForObject("select evaluation::text from matching where id=?", String.class, matching))
			.isEqualTo(evaluation);
	}

	@Test
	void leavesNonReligiousGenerationUntouched() {
		writeState(peer, Map.of("encounter:intake", encode(Map.of("generation", generation)),
			"encounter:workflow", encode(work(2))));
		String before = state(peer);
		retirement.retire(actor);
		assertThat(state(peer)).isEqualTo(before);
	}

	@Test
	void alsoRetiresHistoricalEvidenceWithoutActiveWorkflow() {
		jdbc.update("update matching set evaluation=cast(? as jsonb) where id=?",
			encode(Map.of("historicalEvidence", Map.of("left", input(1)))), matching);
		retirement.retire(actor);
		assertThat(jdbc.queryForObject("select evaluation from matching where id=?", Object.class, matching)).isNull();
	}

	@Test
	void waitsForExistingWriterThenRetiresItsCommittedCopies() throws Exception {
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var writer = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
				jdbc.queryForList("select id from users where id in (?,?) order by id for update",
					Long.class, peer, actor);
				locked.countDown();
				await(release);
				seedCurrent();
			}));
			assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
			var erase = pool.submit(() -> retirement.retire(actor));
			release.countDown();
			writer.get(10, TimeUnit.SECONDS);
			erase.get(10, TimeUnit.SECONDS);
		}
		assertThat(state(peer) + state(actor)).doesNotContain(HASH, OUTER_HASH);
	}

	@Test
	void retiresLegacyHistoricalHashWithoutVersionOrSource() {
		Map<String, Object> legacy = new LinkedHashMap<>();
		legacy.put("userId", actor);
		legacy.put("snapshotId", UUID.randomUUID());
		legacy.put("inputHash", HASH);
		legacy.put("settingsVersion", null);
		legacy.put("inputSourceRef", null);
		jdbc.update("update matching set evaluation=cast(? as jsonb) where id=?",
			encode(Map.of("historicalEvidence", Map.of("left", legacy))), matching);

		retirement.retire(actor);

		assertThat(jdbc.queryForObject("select evaluation from matching where id=?", Object.class, matching)).isNull();
	}

	private void seedCurrent() {
		Map<String, Object> low = new LinkedHashMap<>();
		low.put("encounter:intake", encode(Map.of("generation", generation)));
		low.put("encounter:event:" + event, generation.toString());
		low.put("encounter:workflow", encode(work(1)));
		low.put("encounter:evaluation:" + generation, encode(Map.of("inputHash", OUTER_HASH, "evaluation", input(1))));
		low.put("encounter:proposal:" + generation,
			encode(Map.of("generationId", generation, "inputHash", OUTER_HASH)));
		low.put("unrelated", Map.of("response", "ACCEPT"));
		writeState(peer, low);
		String receipt = encode(Map.of("requestHash", OUTER_HASH, "receipt", Map.of("generationId", generation,
			"evidence", Map.of("settingsVersion", 1, "inputHash", HASH))));
		writeState(actor, Map.of("encounter:verified-input", encode(input(1)),
			"encounter:submission:1:" + submission, receipt, "encounter:event:1:" + event, receipt));
		jdbc.update("update matching_participant set encounter_signature=? where matching_id=?",
			new byte[] {1, 2}, matching);
		jdbc.update("update matching set evaluation=cast(? as jsonb) where id=?",
			encode(Map.of("historicalEvidence", Map.of("left", input(1)))), matching);
	}

	private Map<String, Object> input(long version) {
		return Map.of("userId", actor, "settingsVersion", version, "inputHash", HASH);
	}

	private Map<String, Object> work(long version) {
		return Map.of("generationId", generation, "inputHash", OUTER_HASH,
			"input", Map.of("left", Map.of("userId", peer), "right", input(version)));
	}

	private void assertRetired(UUID submissionId, UUID eventId) {
		assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
			receipts.find(actor, 1, submissionId, eventId, HASH))).isInstanceOfSatisfying(BusinessException.class,
				failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
	}

	private void writeState(long user, Map<String, Object> value) {
		jdbc.update("update matching_participant set request_state=cast(? as jsonb) where matching_id=? and user_id=?",
			encode(value), matching, user);
	}

	private String state(long user) {
		return jdbc.queryForObject("select request_state::text from matching_participant "
			+ "where matching_id=? and user_id=?",
			String.class, matching, user);
	}

	private String encode(Object value) {
		return json.writeValueAsString(value);
	}

	private long user() {
		return jdbc.queryForObject("insert into users(provider,provider_user_id,status,created_at) "
			+ "values ('GOOGLE',?,'ACTIVE',?) returning id", Long.class,
			UUID.randomUUID().toString(), Timestamp.from(NOW));
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Writer was not released");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}
}
