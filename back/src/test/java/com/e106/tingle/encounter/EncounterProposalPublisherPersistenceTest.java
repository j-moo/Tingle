package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.SharedPostgres;
import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.proposal.ProposalPushNotifier;

import tools.jackson.databind.json.JsonMapper;

/** 정상 판정 표시는 이 테스트의 합성 서버 결과이며 실제 기기·평가 연동 근거가 아니다. */
@SpringBootTest(classes = EncounterProposalPublisherPersistenceTest.Config.class, properties = {
	"spring.main.web-application-type=none", "spring.profiles.active=test",
	"spring.jpa.hibernate.ddl-auto=none", "spring.docker.compose.enabled=false",
	"spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:database/schema.sql",
	"app.agreement.items[0].type=TERMS", "app.agreement.items[0].version=test",
	"app.agreement.items[0].required=true"
})
class EncounterProposalPublisherPersistenceTest {
	private static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");
	private static final String HASH = "a".repeat(64);
	private static final Map<String, Object> EVALUATION = Map.of("matched", true, "rules", "v1", "score", 70);

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@Import({EncounterProposalPublisher.class, MemberAccessRepository.class})
	static class Config {
		@Bean
		JdbcConnectionDetails postgresConnection() {
			return SharedPostgres.newDatabase();
		}

		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	@Autowired
	private EncounterProposalPublisher publisher;
	@MockitoBean
	private ProposalPushNotifier pushes;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JsonMapper mapper;
	@Autowired
	private PlatformTransactionManager transactions;

	private long low;
	private long high;
	private long matching;
	private UUID generation;
	private UUID attempt;
	private Map<String, String> state;

	@BeforeEach
	void prepareVerifiedWaitingEncounter() {
		low = user();
		high = user();
		generation = UUID.randomUUID();
		attempt = UUID.randomUUID();
		matching = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,met_at,policy_version,"
			+ "opportunity_expires_at,counterpart_deadline_at,evaluation,status) "
			+ "values (?,?,?,'policy1',?,?,cast(? as jsonb),'WAITING') returning id", Long.class, low, high,
			Timestamp.from(NOW.minusSeconds(3600)), Timestamp.from(NOW.plusSeconds(86400)),
			Timestamp.from(NOW.plusSeconds(3600)), mapper.writeValueAsString(EVALUATION));
		for (long user : new long[] {low, high}) {
			jdbc.update("insert into matching_participant(matching_id,user_id,encounter_keyring_id,"
				+ "encounter_signature,encounter_reported_at,encounter_verified_at,request_state) "
				+ "values (?,?,?,decode('01','hex'),?,?,cast(? as jsonb))", matching, user, user,
				Timestamp.from(NOW.minusSeconds(30)), Timestamp.from(NOW.minusSeconds(20)), "{}");
			jdbc.update("insert into matching_preference(user_id,matching_enabled,age_preference_mode,"
				+ "photo_required) values (?,true,false,false)", user);
			// 새 제안은 키링이 있는 회원만 받는다. 키링 없는 회원 제외는 MemberAccessReadinessPersistenceTest 에서 본다
			jdbc.update("insert into keyring(serial_number,firmware_version,state,owner_user_id) "
				+ "values (?,'test','REGISTERED',?)", "publisher-" + user, user);
		}
		state = new LinkedHashMap<>();
		state.put("safety:test", "preserved");
		state.put("command", "preserved");
		state.put("encounter:submission:1:old", "preserved");
		state.put("encounter:workflow", encodedIdentity());
		Map<String, Object> success = identity();
		success.put("qualified", true);
		success.put("evaluation", EVALUATION);
		state.put("encounter:evaluation:" + generation, mapper.writeValueAsString(success));
		writeState();
	}

	@Test
	void publishesOncePreservingEvidenceReceiptsAndInitialDeadlines() {
		String originalEvaluation = jdbc.queryForObject("select evaluation::text from matching where id=?",
			String.class, matching);
		assertThat(publish()).contains(NOW);
		assertThat(publish()).contains(NOW);
		// 처음 전환 때만 알림을 건다. 같은 평가의 재처리(replay)는 다시 알리지 않는다
		verify(pushes).afterCommit(matching, low, high, NOW);
		assertThat(jdbc.queryForObject("select status::text from matching where id=?", String.class, matching))
			.isEqualTo("PENDING");
		assertThat(time("proposed_at")).isEqualTo(NOW);
		assertThat(time("raw_delete_at")).isEqualTo(NOW.plus(Duration.ofDays(14)));
		assertThat(time("counterpart_deadline_at")).isEqualTo(NOW.plusSeconds(3600));
		assertThat(jdbc.queryForObject("select evaluation::text from matching where id=?", String.class, matching))
			.isEqualTo(originalEvaluation);
		assertThat(jdbc.queryForObject("select request_state->>'safety:test' from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, matching, low)).isEqualTo("preserved");
		assertThat(jdbc.queryForObject("select request_state->>'command' from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, matching, low)).isEqualTo("preserved");
	}

	@Test
	void concurrentPublishersRecoverSameFirstResult() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(() -> {
				assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
				return publish();
			});
			var second = pool.submit(() -> {
				assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
				return publish();
			});
			start.countDown();
			assertThat(first.get(15, TimeUnit.SECONDS)).contains(NOW);
			assertThat(second.get(15, TimeUnit.SECONDS)).contains(NOW);
		}
		assertThat(time("proposed_at")).isEqualTo(NOW);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void eitherMatchingOffStopsOnlyNewPublication(boolean first) {
		jdbc.update("update matching_preference set matching_enabled=false where user_id=?", first ? low : high);
		assertThat(publish()).isEmpty();
		assertThat(time("proposed_at")).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"opportunity_expires_at", "counterpart_deadline_at"})
	void exactDeadlineDoesNotPublish(String column) {
		jdbc.update("update matching set " + column + "=? where id=?", Timestamp.from(NOW), matching);
		assertThat(publish()).isEmpty();
		assertThat(time("proposed_at")).isNull();
	}

	@Test
	void blockedParticipantAndWithdrawnAccountCannotPublish() {
		jdbc.update("update matching_participant set blocked_at=? where matching_id=? and user_id=?",
			Timestamp.from(NOW.minusSeconds(1)), matching, high);
		assertThat(publish()).isEmpty();
		jdbc.update("update matching_participant set blocked_at=null where matching_id=?", matching);
		jdbc.update("update users set status='WITHDRAWN' where id=?", low);
		assertThat(publish()).isEmpty();
	}

	@Test
	void incompleteOrRevokedAgreementCannotPublish() {
		jdbc.update("update users set signup_completed_at=null where id=?", low);
		assertThat(publish()).isEmpty();
		jdbc.update("update users set signup_completed_at=? where id=?", Timestamp.from(NOW), low);
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (?,'TERMS','test',false,?)", high, Timestamp.from(NOW.plusSeconds(1)));
		assertThat(publish()).isEmpty();
	}

	@Test
	void anyExistingChatPreventsASecondProposal() {
		jdbc.update("insert into chat(matching_id,created_at,last_message_seq,icebreakers,ai_state,expires_at) "
			+ "values (?,?,0,'[]','{}',?)", matching, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(10)));
		assertThat(publish()).isEmpty();
	}

	@Test
	void staleGenerationAttemptHashAndChangedStoredEvaluationCannotPublish() {
		assertThat(tx(() -> publisher.publish(matching, UUID.randomUUID(), attempt, HASH))).isEmpty();
		assertThat(tx(() -> publisher.publish(matching, generation, UUID.randomUUID(), HASH))).isEmpty();
		assertThat(tx(() -> publisher.publish(matching, generation, attempt, "b".repeat(64)))).isEmpty();
		jdbc.update("update matching set evaluation='{}' where id=?", matching);
		assertThat(publish()).isEmpty();
	}

	@Test
	void retainedOldSuccessCannotPublishANewWaitingGeneration() {
		Map<String, Object> current = identity();
		current.put("generationId", UUID.randomUUID());
		state.put("encounter:workflow", mapper.writeValueAsString(current));
		writeState();
		assertThat(publish()).isEmpty();
	}

	@Test
	void absentCorruptOrUnqualifiedEvaluationNeverBecomesAProposal() {
		String resultKey = "encounter:evaluation:" + generation;
		state.remove(resultKey);
		writeState();
		assertThat(publish()).isEmpty();
		state.put(resultKey, "{}");
		writeState();
		assertThat(publish()).isEmpty();
		Map<String, Object> result = identity();
		result.put("qualified", false);
		result.put("evaluation", EVALUATION);
		state.put(resultKey, mapper.writeValueAsString(result));
		writeState();
		assertThat(publish()).isEmpty();
	}

	@Test
	void partialProofParticipantDecisionAndLegacyHistoryPreventPublication() {
		jdbc.update("update matching_participant set encounter_signature=null where matching_id=? and user_id=?",
			matching, high);
		assertThat(publish()).isEmpty();
		jdbc.update("update matching_participant set encounter_signature=decode('01','hex'),response='REJECTED' "
			+ "where matching_id=? and user_id=?", matching, high);
		assertThat(publish()).isEmpty();
		jdbc.update("update matching_participant set response=null where matching_id=?", matching);
		jdbc.update("update matching set legacy_state='{\"previousProposal\":1}' where id=?", matching);
		assertThat(publish()).isEmpty();
	}

	@Test
	void replayAfterCloseOrMatchingOffDoesNotResurrectOrChangeFirstTime() {
		assertThat(publish()).contains(NOW);
		jdbc.update("update matching set status='CLOSED' where id=?", matching);
		jdbc.update("update matching_preference set matching_enabled=false where user_id=?", low);
		assertThat(publish()).contains(NOW);
		assertThat(jdbc.queryForObject("select status::text from matching where id=?", String.class, matching))
			.isEqualTo("CLOSED");
		assertThat(time("proposed_at")).isEqualTo(NOW);
	}

	@Test
	void seededProposalWithoutOwnMarkerIsNotReportedAsPublishedAndCannotBeRecreated() {
		jdbc.update("update matching set status='PENDING',proposed_at=? where id=?", Timestamp.from(NOW), matching);
		assertThat(publish()).isEmpty();
		jdbc.update("update matching set status='WAITING' where id=?", matching);
		assertThat(publish()).isEmpty();
		jdbc.update("update matching set proposed_at=null where id=?", matching);
		state.put("encounter:proposal:" + UUID.randomUUID(), "historical");
		writeState();
		assertThat(publish()).isEmpty();
	}

	@Test
	void requiresCallingTransaction() {
		assertThatThrownBy(() -> publisher.publish(matching, generation, attempt, HASH))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	private Optional<Instant> publish() {
		return tx(() -> publisher.publish(matching, generation, attempt, HASH));
	}

	private <T> T tx(Supplier<T> work) {
		return new TransactionTemplate(transactions).execute(transaction -> work.get());
	}

	private Map<String, Object> identity() {
		Map<String, Object> identity = new LinkedHashMap<>();
		identity.put("generationId", generation);
		identity.put("attemptId", attempt);
		identity.put("inputHash", HASH);
		identity.put("state", "SUCCEEDED");
		return identity;
	}

	private String encodedIdentity() {
		return mapper.writeValueAsString(identity());
	}

	private void writeState() {
		jdbc.update("update matching_participant set request_state=cast(? as jsonb) where matching_id=? and user_id=?",
			mapper.writeValueAsString(state), matching, low);
	}

	private Instant time(String column) {
		return jdbc.queryForObject("select " + column + " from matching where id=?",
			(rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), matching);
	}

	private long user() {
		long id = jdbc.queryForObject("insert into users(provider,provider_user_id,status,"
			+ "signup_completed_at,created_at) "
			+ "values ('GOOGLE',?,'ACTIVE',?,?) returning id", Long.class, UUID.randomUUID().toString(),
			Timestamp.from(NOW.minusSeconds(3600)), Timestamp.from(NOW.minusSeconds(3600)));
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (?,'TERMS','test',true,?)", id, Timestamp.from(NOW.minusSeconds(3600)));
		return id;
	}
}
