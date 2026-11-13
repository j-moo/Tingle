package com.e106.tingle.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.SharedPostgres;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Context;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Inventory;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Reference;
import com.e106.tingle.chat.deletion.ChatDeletionRepository;
import com.e106.tingle.chat.deletion.ChatDeletionService;
import com.e106.tingle.chat.deletion.ChatDeletionService.Claim;
import com.e106.tingle.chat.deletion.ChatDeletionService.Outcome;
import com.e106.tingle.chat.deletion.ChatDeletionState;
import com.e106.tingle.chat.deletion.ChatDeletionState.Status;
import com.e106.tingle.chat.deletion.restore.DeletionJournal;
import com.e106.tingle.encounter.privacy.KeyringPeerDataRetirement;
import com.e106.tingle.settings.snapshot.SettingsChangeCoordinator;

import tools.jackson.databind.json.JsonMapper;

/** Disposable PostgreSQL checks real cleanup SQL without shared data or external services. */
class MemberWithdrawalCleanupIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-11-06T04:00:00Z");
	private static final JdbcConnectionDetails POSTGRES = SharedPostgres.newDatabase();
	private JdbcTemplate jdbc;
	private TransactionTemplate transaction;
	private SettingsChangeCoordinator changes;
	private MemberWithdrawalCleanup cleanup;
	private ChatDeletionRepository deletionRepository;
	private DataSourceTransactionManager manager;

	@BeforeEach
	void prepareSchema() {
		JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(), POSTGRES.getPassword()));
		String schema = "withdrawal_" + UUID.randomUUID().toString().replace("-", "");
		admin.execute("create schema " + schema);
		String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
			+ "currentSchema=" + schema;
		DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
			POSTGRES.getUsername(), POSTGRES.getPassword());
		new ResourceDatabasePopulator(new ClassPathResource("database/schema.sql")).execute(dataSource);
		jdbc = new JdbcTemplate(dataSource);
		manager = new DataSourceTransactionManager(dataSource);
		transaction = new TransactionTemplate(manager);
		changes = mock(SettingsChangeCoordinator.class);
		deletionRepository = new ChatDeletionRepository(jdbc, JsonMapper.builder().build());
		cleanup = new MemberWithdrawalCleanup(jdbc, changes,
			new MemberWithdrawalDeletionInvalidator(deletionRepository), new KeyringPeerDataRetirement(jdbc));
		jdbc.update("insert into users(provider,provider_user_id,status,created_at) "
			+ "values ('GOOGLE','departing','ACTIVE',?),('GOOGLE','remaining','ACTIVE',?)",
			Timestamp.from(NOW), Timestamp.from(NOW));
	}

	@Test
	void retainsReportMessagesAndDeletionWorkerWhileRemovingMemberReferences() {
		long matching = matching();
		long room = room(matching, NOW.plus(2, ChronoUnit.DAYS), null);
		long participant = participant(room, 1);
		participant(room, 2);
		long message = jdbc.queryForObject("insert into chat_message(chat_id,sender_participant_id,"
			+ "client_message_id,request_key,request_hash,seq,body,created_at) values (?,?,?,?,?,1,'evidence',?) "
			+ "returning id", Long.class, room, participant, UUID.randomUUID(), UUID.randomUUID(), new byte[32],
			Timestamp.from(NOW));
		long report = jdbc.queryForObject("insert into chat_report(chat_id,reporter_participant_id,reason,status,"
			+ "created_at) values (?,?,'ABUSE','RECEIVED',?) returning id", Long.class, room, participant,
			Timestamp.from(NOW));
		jdbc.update("insert into chat_report_message(report_id,message_id) values (?,?)", report, message);
		Instant reportDue = NOW.plus(90, ChronoUnit.DAYS);
		jdbc.update("update chat set retain_until=? where id=?", Timestamp.from(reportDue), room);
		assertThat(cleanup.relatedMemberIds(1)).containsExactly(1L, 2L);
		remove(1);
		assertThat(jdbc.queryForObject("select count(*) from users where id=1", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select user_id from chat_participant where id=?", Long.class, participant))
			.isNull();
		assertThat(jdbc.queryForObject("select body from chat_message where id=?", String.class, message))
			.isEqualTo("evidence");
		assertThat(jdbc.queryForObject("select count(*) from chat_report_message", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select delete_at from chat where id=?", Timestamp.class, room).toInstant())
			.isEqualTo(reportDue);
		assertThat(jdbc.queryForObject("select user_low_id is null and user_high_id is null and status='CLOSED' "
			+ "and evaluation is null and legacy_state is null from matching where id=?", Boolean.class, matching))
			.isTrue();
		verify(changes).advanceAndCapture(2);
		ChatDeletionRepository worker = new ChatDeletionRepository(jdbc, JsonMapper.builder().build());
		transaction.executeWithoutResult(status -> assertThat(worker.lockNext(reportDue.plusSeconds(1)))
			.contains(matching));
	}

	@Test
	void preservesOriginalExpiredAndAlreadyClosedRetentionTimes() {
		long matching = matching();
		Instant expired = NOW.minus(4, ChronoUnit.DAYS);
		long room = room(matching, expired, null);
		participant(room, 1);
		participant(room, 2);
		remove(1);
		assertThat(jdbc.queryForObject("select closed_at from chat where id=?", Timestamp.class, room).toInstant())
			.isEqualTo(expired);
		assertThat(jdbc.queryForObject("select delete_at from chat where id=?", Timestamp.class, room).toInstant())
			.isEqualTo(expired.plus(30, ChronoUnit.DAYS));
		remove(2);
		assertThat(jdbc.queryForObject("select delete_at from chat where id=?", Timestamp.class, room).toInstant())
			.isEqualTo(expired.plus(30, ChronoUnit.DAYS));
	}

	@Test
	void deletesUnretainedMatchingAndRollsBackAllCleanupWhenCallerFails() {
		long matching = matching();
		jdbc.update("insert into profile(user_id,nickname) values (1,'departing')");
		transaction.executeWithoutResult(status -> {
			cleanup.cleanupUnderLocks(1, NOW);
			status.setRollbackOnly();
		});
		assertThat(jdbc.queryForObject("select count(*) from users where id=1", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from profile where user_id=1", Integer.class)).isEqualTo(1);
		remove(1);
		assertThat(jdbc.queryForObject("select count(*) from matching where id=?", Integer.class, matching)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from profile where user_id=1", Integer.class)).isZero();
	}

	@Test
	void suspendedCounterpartDoesNotBlockRemovalOrCreateAnActiveSnapshot() {
		matching();
		jdbc.update("update users set status='SUSPENDED' where id=2");
		jdbc.update("insert into user_setting(user_id,vibration_enabled,led_enabled,settings_version) "
			+ "values (2,true,true,7)");
		remove(1);
		assertThat(jdbc.queryForObject("select count(*) from users where id=1", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select settings_version from user_setting where user_id=2", Long.class))
			.isEqualTo(8);
		assertThat(jdbc.queryForObject("select count(*) from settings_snapshot where user_id=2", Integer.class))
			.isZero();
		verify(changes, never()).advanceAndCapture(2);
	}

	@Test
	void removesDevelopmentPhoneModeRowsOfTheMemberAndEncountersNamingThem() {
		new ResourceDatabasePopulator(new ClassPathResource("database/phone-mode-262.sql"))
			.execute(jdbc.getDataSource());
		UUID departing = phoneDevice(1);
		UUID remaining = phoneDevice(2);
		UUID shared = phoneEncounter("shared");
		UUID waiting = phoneEncounter("waiting");
		UUID own = phoneEncounter("own");
		phoneToken(departing, shared);
		phoneToken(remaining, shared);
		phoneToken(remaining, waiting);
		phoneToken(remaining, own);
		phoneToken(departing, null);
		jdbc.update("insert into phone_mode_submission values (?,1,2),(?,2,1),(?,2,1),(?,2,3)",
			shared, shared, waiting, own);
		jdbc.update("insert into phone_mode_evaluation values (?,'UNAVAILABLE','SNAPSHOT_UNAVAILABLE',?)",
			shared, Timestamp.from(NOW));
		jdbc.update("insert into phone_mode_evaluation_input values (?,1,1,1),(?,2,2,1)", shared, shared);
		jdbc.update("insert into phone_mode_request values (1,'submit',?,'h',?,?),(2,'submit',?,'h',?,?)",
			UUID.randomUUID(), shared, Timestamp.from(NOW), UUID.randomUUID(), own, Timestamp.from(NOW));

		remove(1);

		for (String table : List.of("phone_mode_evaluation_input", "phone_mode_evaluation")) {
			assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).isZero();
		}
		assertThat(jdbc.queryForList("select id from phone_mode_encounter", UUID.class)).containsExactly(own);
		assertThat(jdbc.queryForList("select device_id from phone_mode_token", UUID.class)).containsExactly(remaining);
		assertThat(jdbc.queryForList("select user_id from phone_mode_device", Long.class)).containsExactly(2L);
		assertThat(jdbc.queryForList("select user_id from phone_mode_submission", Long.class)).containsExactly(2L);
		assertThat(jdbc.queryForList("select user_id from phone_mode_request", Long.class)).containsExactly(2L);
	}

	private UUID phoneDevice(long userId) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into phone_mode_device values (?,?,?,true)", id, userId, UUID.randomUUID());
		return id;
	}

	private UUID phoneEncounter(String exchange) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into phone_mode_encounter(id,exchange_hash,met_at,expires_at) values (?,?,?,?)",
			id, exchange, Timestamp.from(NOW), Timestamp.from(NOW));
		return id;
	}

	private void phoneToken(UUID device, UUID encounter) {
		jdbc.update("insert into phone_mode_token(id,device_id,token_hash,expires_at,encounter_id) "
			+ "values (?,?,?,?,?)", UUID.randomUUID(), device, UUID.randomUUID().toString(), Timestamp.from(NOW),
			encounter);
	}

	@Test
	void redactionRevokesOldDeletionApprovalAndRequiresAllKnownCopiesForReinspection() {
		long matching = matching();
		Instant closed = NOW.minus(40, ChronoUnit.DAYS);
		long room = room(matching, NOW.minus(33, ChronoUnit.DAYS), closed);
		participant(room, 1);
		participant(room, 2);
		jdbc.update("update chat set icebreakers='{\"requestedBy\":1}' where id=?", room);
		Reference oldCopy = new Reference("backup", "v1", "old-copy");
		Context oldContext = transaction.execute(status -> deletionRepository.lockContext(matching, NOW, null)
			.orElseThrow());
		ChatDeletionState previous = new ChatDeletionState(1, UUID.randomUUID(), oldContext, Status.RUNNING,
			1, NOW.plusSeconds(60), null, new Inventory(true, false, "old-approval", List.of(oldCopy)), null, null);
		deletionRepository.save(matching, previous);
		Claim oldClaim = new Claim(matching, room, previous.generation(), previous.attempt());
		remove(1);
		ChatDeletionState invalidated = deletionRepository.state(matching).orElseThrow();
		assertThat(invalidated.inventory().references()).containsExactly(oldCopy);
		assertThat(invalidated.inventory().authorizesDeletion()).isFalse();
		assertThat(invalidated.status()).isEqualTo(Status.BLOCKED_REFERENCES);
		assertThat(invalidated.context().sourceFingerprint()).isNotEqualTo(oldContext.sourceFingerprint());
		ChatDeletionReferenceSource source = mock(ChatDeletionReferenceSource.class);
		ChatDeletionService worker = new ChatDeletionService(deletionRepository, source,
			Clock.fixed(NOW, ZoneOffset.UTC), mock(DeletionJournal.class), manager);
		assertThat(worker.deleteClaim(oldClaim)).isEqualTo(Outcome.STALE);
		when(source.inspect(invalidated.context()))
			.thenReturn(new Inventory(true, true, "missing-old-copy", List.of()));
		var missingReferences = transaction.execute(status -> worker.claimNext());
		assertThat(missingReferences).isEmpty();
		assertThat(deletionRepository.state(matching).orElseThrow().inventory().references()).containsExactly(oldCopy);
		jdbc.update("update matching set chat_deletion_state=jsonb_set(chat_deletion_state,'{nextAttemptAt}',"
			+ "to_jsonb(cast(? as text))) where id=?", NOW.toString(), matching);
		when(source.inspect(invalidated.context())).thenReturn(new Inventory(true, false, "fresh-approval",
			List.of(oldCopy)));
		var renewedApproval = transaction.execute(status -> worker.claimNext());
		assertThat(renewedApproval).isPresent();
		assertThat(deletionRepository.state(matching).orElseThrow().inventory().approvalId())
			.isEqualTo("fresh-approval");
	}

	private void remove(long userId) {
		transaction.executeWithoutResult(status -> {
			for (long member : cleanup.relatedMemberIds(userId)) {
				jdbc.queryForList("select id from users where id=? for update", Long.class, member);
			}
			cleanup.cleanupUnderLocks(userId, NOW);
		});
	}

	private long matching() {
		long id = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,status,evaluation,legacy_state) "
			+ "values (1,2,'ACCEPTED','{\"private\":1}','{\"private\":1}') returning id", Long.class);
		jdbc.update("insert into matching_participant(matching_id,user_id) values (?,1),(?,2)", id, id);
		return id;
	}

	private long room(long matching, Instant expiry, Instant closed) {
		return jdbc.queryForObject("insert into chat(matching_id,created_at,expires_at,closed_at,last_message_seq,"
			+ "icebreakers,ai_state) values (?,?,?,?,0,'{}','{}') returning id", Long.class, matching,
			Timestamp.from(NOW.minus(10, ChronoUnit.DAYS)), Timestamp.from(expiry),
			closed == null ? null : Timestamp.from(closed));
	}

	private long participant(long room, long user) {
		return jdbc.queryForObject("insert into chat_participant(chat_id,user_id) values (?,?) returning id",
			Long.class, room, user);
	}
}
