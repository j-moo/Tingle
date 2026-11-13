package com.e106.tingle.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.e106.tingle.SharedPostgres;

class MatchingLegacyMigrationTest {

	private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
	private static final JdbcConnectionDetails POSTGRES = SharedPostgres.newDatabase();
	private static DriverManagerDataSource source;
	private static JdbcTemplate jdbc;

	@BeforeAll
	static void connect() {
		source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(source);
	}

	@BeforeEach
	void prepare() {
		// Only this test's disposable PostgreSQL schema is recreated.
		jdbc.execute("drop schema public cascade");
		jdbc.execute("create schema public");
		jdbc.execute("drop schema if exists shadow cascade");
		new ResourceDatabasePopulator(new ClassPathResource("matching-175-legacy.sql")).execute(source);
	}

	@Test
	void preservesIdsFirstResponseCancellationAndFullSourceEvidence() throws Exception {
		long id = seed(1, 2);
		UUID request = UUID.randomUUID();
		UUID cancel = UUID.randomUUID();
		jdbc.update("update match_proposals set low_decision='ACCEPT',low_decided_at=?,low_request_key=?,"
			+ "low_cancelled_at=?,low_cancel_request_key=?,status='REJECTED' where proposal_id=?",
			Timestamp.from(NOW), request, Timestamp.from(NOW.plusSeconds(60)), cancel, id);
		jdbc.update("insert into user_blocks(blocker_id,blocked_id,created_at) values(2,1,?)", Timestamp.from(NOW));
		jdbc.execute("select setval(pg_get_serial_sequence('match_proposals','proposal_id'),5000,true)");
		migrate();
		assertThat(jdbc.queryForObject("select status::text from matching where id=?", String.class, id))
			.isEqualTo("REJECTED");
		assertThat(jdbc.queryForObject("select proposed_at from matching where id=?", Timestamp.class, id))
			.isEqualTo(Timestamp.from(NOW));
		assertThat(jdbc.queryForObject("select legacy_state->'pair'->>'pair_id' from matching where id=?",
			String.class, id)).isEqualTo("1");
		assertThat(jdbc.queryForObject("select legacy_state->'proposal'->>'proposal_id' from matching where id=?",
			String.class, id)).isEqualTo(Long.toString(id));
		assertThat(jdbc.queryForObject("select response::text from matching_participant "
			+ "where matching_id=? and user_id=1", String.class, id)).isEqualTo("ACCEPTED");
		assertThat(jdbc.queryForObject("select decision_request_key from matching_participant "
			+ "where matching_id=? and user_id=1", UUID.class, id)).isEqualTo(request);
		assertThat(jdbc.queryForObject("select request_state->>'legacyCancelRequestKey' "
			+ "from matching_participant where matching_id=? and user_id=1", String.class, id))
			.isEqualTo(cancel.toString());
		assertThat(jdbc.queryForObject("select blocked_at from matching_participant "
			+ "where matching_id=? and user_id=2", Timestamp.class, id)).isEqualTo(Timestamp.from(NOW));
		assertThat(jdbc.queryForObject("select count(*) from matching_participant", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("insert into matching(user_low_id,user_high_id,status) "
			+ "values(3,4,'WAITING') returning id", Long.class)).isEqualTo(5001L);
		assertThat(jdbc.queryForObject("select to_regclass('public.match_proposals')::text", String.class)).isNull();
		assertThat(jdbc.queryForObject("select to_regclass('public.user_pairs')::text", String.class)).isNull();
	}

	@Test
	void rejectsSeveralProposalsForOnePairWithoutOverwritingAnyId() {
		seed(1, 2);
		Long encounter = jdbc.queryForObject("insert into encounters(pair_id,event_key,occurred_at,"
			+ "opportunity_expires_at,counterpart_deadline_at,status) "
			+ "select pair_id,'second',occurred_at,opportunity_expires_at,counterpart_deadline_at,status "
			+ "from encounters where encounter_id=1 returning encounter_id", Long.class);
		jdbc.update("insert into match_proposals(pair_id,encounter_id,status,created_at,expires_at) "
			+ "values (1,?,'PENDING',?,?)", encounter, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(86400)));
		assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
		assertSource(2);
	}

	@Test
	void rejectsExistingCanonicalTargetWithoutMergingRows() {
		seed(1, 2);
		jdbc.execute("create table matching(id bigint primary key)");
		assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
		assertThat(jdbc.queryForObject("select count(*) from match_proposals", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from matching", Integer.class)).isZero();
	}

	@Test
	void rejectsRequestKeyReuseAcrossOriginalPairs() {
		seed(1, 2);
		seed(1, 3);
		jdbc.update("update match_proposals set low_decision='ACCEPT',low_decided_at=?,low_request_key=?",
			Timestamp.from(NOW), UUID.randomUUID());
		assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
		assertSource(2);
	}

	@Test
	void rejectsBlockWithoutProvenProposalIdentity() {
		seed(1, 2);
		jdbc.update("insert into user_blocks(blocker_id,blocked_id,created_at) values(3,4,?)", Timestamp.from(NOW));
		assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
		assertSource(1);
		assertThat(jdbc.queryForObject("select count(*) from user_blocks", Integer.class)).isEqualTo(1);
	}

	@Test
	void rejectsStatusWithoutOriginalResponseEvidenceRatherThanInventingAcceptance() {
		seed(1, 2);
		jdbc.update("update match_proposals set status='ACCEPTED'");
		assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
		assertSource(1);
		assertThat(jdbc.queryForObject("select status from match_proposals", String.class)).isEqualTo("ACCEPTED");
	}

	@Test
	void migratesOnlyPublicSourcesDespiteNonDefaultSearchPathAndIdenticalShadowTables() throws Exception {
		seed(1, 2);
		jdbc.execute("create schema shadow");
		for (String name : List.of("user_pairs", "encounters", "encounter_submissions", "match_proposals",
			"user_blocks")) {
			jdbc.execute("create table shadow." + name + " (like public." + name + " including all)");
			jdbc.execute("insert into shadow." + name + " overriding system value select * from public." + name);
		}
		migrate(true);
		assertThat(jdbc.queryForObject("select count(*) from public.matching", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from public.matching_participant", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("select to_regclass('public.match_proposals')::text", String.class)).isNull();
		assertThat(jdbc.queryForObject("select count(*) from shadow.match_proposals", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from shadow.user_pairs", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from shadow.encounters", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select to_regclass('shadow.matching')::text", String.class)).isNull();
		assertThat(jdbc.queryForObject("select status from shadow.match_proposals", String.class)).isEqualTo("PENDING");
	}

	@Test
	void refusesAnUnverifiedEncounterWithoutPublishingItAsAnActiveProposal() {
		seed(1, 2);
		jdbc.update("update encounters set status='PENDING'");
		assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
		assertSource(1);
		assertThat(jdbc.queryForObject("select status from encounters", String.class)).isEqualTo("PENDING");
	}

	@Test
	void preservesTemporarySourcesInTheSamePhysicalConnection() throws Exception {
		seed(1, 2);
		String sql = Files.readString(Path.of("database/matching-175-migration.sql"));
		try (Connection connection = source.getConnection()) {
			JdbcTemplate session = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
			for (String name : List.of("user_pairs", "encounters", "encounter_submissions", "match_proposals",
				"user_blocks")) {
				session.execute("create temporary table " + name + " (like public." + name + " including all)");
				session.execute("insert into pg_temp." + name
					+ " overriding system value select * from public." + name);
			}
			session.execute(sql);
			assertThat(session.queryForObject("select count(*) from public.matching", Integer.class)).isEqualTo(1);
			assertThat(session.queryForObject("select to_regclass('public.match_proposals')::text", String.class))
				.isNull();
			assertThat(session.queryForObject("select count(*) from pg_temp.match_proposals", Integer.class))
				.isEqualTo(1);
			assertThat(session.queryForObject("select count(*) from pg_temp.user_pairs", Integer.class)).isEqualTo(1);
			assertThat(session.queryForObject("select count(*) from pg_temp.encounters", Integer.class)).isEqualTo(1);
			assertThat(session.queryForObject("select to_regclass('pg_temp.encounter_submissions')::text",
				String.class)).isNotNull();
			assertThat(session.queryForObject("select to_regclass('pg_temp.user_blocks')::text", String.class))
				.isNotNull();
			assertThat(session.queryForObject("select status from pg_temp.match_proposals", String.class))
				.isEqualTo("PENDING");
		}
	}

	private long seed(long low, long high) {
		Long pair = jdbc.queryForObject("insert into user_pairs(user_low,user_high) values(?,?) returning pair_id",
			Long.class, low, high);
		Long encounter = jdbc.queryForObject("insert into encounters(pair_id,event_key,occurred_at,"
			+ "opportunity_expires_at,counterpart_deadline_at,status) values(?,?,?,?,?,'VERIFIED') "
			+ "returning encounter_id", Long.class, pair, UUID.randomUUID().toString(), Timestamp.from(NOW),
			Timestamp.from(NOW.plusSeconds(604800)), Timestamp.from(NOW.plusSeconds(604800)));
		return jdbc.queryForObject("insert into match_proposals(pair_id,encounter_id,status,created_at,expires_at) "
			+ "values(?,?,'PENDING',?,?) returning proposal_id", Long.class, pair, encounter,
			Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(86400)));
	}

	private void migrate() throws Exception {
		migrate(false);
	}

	private void migrate(boolean shadowFirst) throws Exception {
		String sql = Files.readString(Path.of("database/matching-175-migration.sql"));
		try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
			if (shadowFirst) {
				statement.execute("set search_path to shadow,public");
			}
			statement.execute(sql);
		}
	}

	private void assertSource(int count) {
		assertThat(jdbc.queryForObject("select count(*) from match_proposals", Integer.class)).isEqualTo(count);
		assertThat(jdbc.queryForObject("select to_regclass('public.matching')::text", String.class)).isNull();
		assertThat(jdbc.queryForObject("select to_regclass('public.matching_participant')::text", String.class))
			.isNull();
	}
}
