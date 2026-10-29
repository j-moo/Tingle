package com.e106.tingle.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ChatLegacyMigrationTest {

	private static final Instant CREATED = Instant.parse("2026-10-27T00:00:00Z");
	private static final Instant DUE = CREATED.plusSeconds(167 * 3600);
	private static final Instant DISPLAYED = CREATED.plusSeconds(2);

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
	private static DriverManagerDataSource source;
	private static JdbcTemplate jdbc;

	@BeforeAll
	static void connectToDisposableDatabase() {
		source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(source);
	}

	@BeforeEach
	void createCanonicalSurroundingsAndKnownLegacyChatSources() {
		// This database exists only inside the test container above.
		jdbc.execute("drop schema public cascade");
		jdbc.execute("create schema public");
		new ResourceDatabasePopulator(new ClassPathResource("database/schema.sql")).execute(source);
		jdbc.execute("drop table chat_message");
		jdbc.execute("drop table chat_participant");
		jdbc.execute("drop table chat");
		new ResourceDatabasePopulator(new ClassPathResource("chat-175-legacy.sql")).execute(source);
	}

	@Test
	@DisplayName("25표로 이관해도 번호·수명·안내 UUID·선점·전달 영수증·불연속 읽음 증거를 보존한다")
	void preservesDurableChatAndNoticeEvidenceWithoutFillingReadGaps() throws Exception {
		Legacy legacy = seedLegacyChat();
		jdbc.queryForObject("select setval(pg_get_serial_sequence('chat_rooms','chat_room_id'),4000,true)", Long.class);
		jdbc.queryForObject("select setval(pg_get_serial_sequence('chat_messages','message_id'),5000,true)",
			Long.class);
		migrate();
		assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='public' "
			+ "and table_type='BASE TABLE'", Integer.class)).isEqualTo(25);
		assertThat(jdbc.queryForMap("select legacy_public_id,matching_id,created_at,expires_at,retain_until "
			+ "from chat where id=?", legacy.roomId()))
			.containsEntry("legacy_public_id", legacy.roomUuid()).containsEntry("matching_id", 20L)
			.containsEntry("created_at", Timestamp.from(CREATED))
			.containsEntry("expires_at", Timestamp.from(CREATED.plusSeconds(168 * 3600)))
			.containsEntry("retain_until", null);
		Map<String, Object> notice = jdbc.queryForMap("select notice_state->'END_NOTICE'->>'noticeId' as id,"
			+ "notice_state->'END_NOTICE'->>'attempts' as attempts,notice_state->'END_NOTICE'->>'leaseUntil' as lease,"
			+ "notice_state->'END_NOTICE'->'resultJson'->>? as delivery from chat_participant "
			+ "where chat_id=? and user_id=2", legacy.binding().toString(), legacy.roomId());
		assertThat(notice).containsEntry("id", legacy.noticeId().toString()).containsEntry("attempts", "2")
			.containsEntry("delivery", "ACCEPTED");
		assertThat(Instant.parse((String)notice.get("lease"))).isEqualTo(DUE.plusSeconds(30));
		assertThat(jdbc.queryForObject("select cast(notice_state->'FIRST_ENTRY'->>'ackedAt' as timestamptz) "
			+ "from chat_participant where chat_id=? and user_id=2", Timestamp.class, legacy.roomId()).toInstant())
			.isEqualTo(DISPLAYED);
		assertThat(jdbc.queryForObject("select cast(read_state->?->>'readAt' as timestamptz) from chat_participant "
			+ "where chat_id=? and user_id=2", Timestamp.class, Long.toString(legacy.lastMessage()), legacy.roomId())
			.toInstant()).isEqualTo(DISPLAYED);
		assertThat(jdbc.queryForObject("select last_read_message_seq from chat_participant "
			+ "where chat_id=? and user_id=2", Long.class, legacy.roomId())).isZero();
		assertThat(jdbc.queryForObject("select encode(request_hash,'hex') from chat_message where id=?", String.class,
			legacy.lastMessage())).isEqualTo(ChatText.hash("third"));
		assertThat(jdbc.queryForObject("select to_regclass('public.background_jobs')::text", String.class)).isNull();
		assertThat(jdbc.queryForObject("select to_regclass('public.chat_message_reads')::text", String.class)).isNull();
		assertThat(jdbc.queryForObject("insert into chat(matching_id,created_at,expires_at,last_message_seq,"
			+ "icebreakers,ai_state) values (30,?,?,0,'{}','{}') returning id", Long.class, Timestamp.from(CREATED),
			Timestamp.from(CREATED.plusSeconds(168 * 3600)))).isEqualTo(4001L);
		Long sender = jdbc.queryForObject("select id from chat_participant where chat_id=? and user_id=1",
			Long.class, legacy.roomId());
		assertThat(jdbc.queryForObject("insert into chat_message(chat_id,seq,sender_participant_id,body,"
			+ "client_message_id,request_key,request_hash,created_at) values (?,4,?,'new',?,?,?,?) returning id",
			Long.class, legacy.roomId(), sender, UUID.randomUUID(), UUID.randomUUID(), new byte[32],
			Timestamp.from(CREATED))).isEqualTo(5001L);
	}

	@Test
	@DisplayName("다른 업무의 background job이 있으면 삭제하거나 안내 JSON에 숨기지 않고 원본을 유지한다")
	void refusesUnsupportedJobScopesAtomically() {
		seedLegacyChat();
		jdbc.update("insert into background_jobs(public_id,job_type,business_key,payload,status,run_at) "
			+ "values (?,'AI','unrelated-ai','{}','PENDING',?)", UUID.randomUUID(), Timestamp.from(CREATED));
		assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
		assertOriginalTablesRemain();
		assertThat(jdbc.queryForObject("select count(*) from background_jobs where job_type='AI'", Integer.class))
			.isEqualTo(1);
	}

	@Test
	@DisplayName("설치 연결 세대로 입증되지 않은 전달 영수증은 추정 변환하지 않는다")
	void refusesUnprovedDeliveryReceiptsAtomically() {
		seedLegacyChat();
		jdbc.update("update background_jobs set result_json='{\"unknown-binding\":\"ACCEPTED\"}' "
			+ "where job_type='END_NOTICE' and payload->>'userId'='2'");
		assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
		assertOriginalTablesRemain();
	}

	@Test
	@DisplayName("최초 ACK의 원래 안내 UUID가 없으면 새 UUID나 새 시각으로 메우지 않는다")
	void refusesAnAcknowledgementWithoutItsNoticeIdentity() {
		seedLegacyChat();
		jdbc.update("delete from background_jobs where job_type='FIRST_ENTRY'");
		assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
		assertOriginalTablesRemain();
	}

	@Test
	@DisplayName("과거 요청 키의 null·잘못된 UUID를 지우거나 새 키로 추정하지 않는다")
	void refusesMalformedOptionalRequestKeysWithoutDroppingTheirEvidence() {
		seedLegacyChat();
		for (String key : List.of("null", "\"not-a-uuid\"")) {
			jdbc.update("update background_jobs set payload=jsonb_set(payload,'{entryKey}',cast(? as jsonb)) "
				+ "where job_type='FIRST_ENTRY'", key);
			assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
			assertOriginalTablesRemain();
			assertThat(jdbc.queryForObject("select payload->'entryKey'=cast(? as jsonb) from background_jobs "
				+ "where job_type='FIRST_ENTRY'", Boolean.class, key)).isTrue();
		}
	}

	@Test
	@DisplayName("원래 관계와 공개 제안 연결 증거가 없으면 다른 matching에 방을 연결하지 않는다")
	void refusesAnUnprovedMatchingMapping() {
		seedLegacyChat();
		jdbc.update("update matching set legacy_state=null where id=20");
		assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
		assertOriginalTablesRemain();
	}

	@Test
	@DisplayName("방 수신 참여자가 아닌 읽음 영수증은 조용히 누락시키지 않고 전체 이관을 거부한다")
	void refusesForeignReadReceipts() {
		Legacy legacy = seedLegacyChat();
		jdbc.update("insert into chat_message_reads(message_id,user_id,read_at) values (?,3,?)",
			legacy.lastMessage(), Timestamp.from(DISPLAYED));
		assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
		assertOriginalTablesRemain();
		assertThat(jdbc.queryForObject("select count(*) from chat_message_reads", Integer.class)).isEqualTo(2);
	}

	@Test
	@DisplayName("다른 search_path의 동명 표는 보존하고 검사·잠금한 public 채팅 자료만 옮긴다")
	void migratesOnlyTheGuardedPublicChatDespiteAnotherSearchPath() throws Exception {
		seedLegacyChat();
		jdbc.execute("create schema shadow");
		jdbc.execute("create table shadow.chat_rooms (like public.chat_rooms including all)");
		UUID shadowUuid = UUID.randomUUID();
		jdbc.update("insert into shadow.chat_rooms(pair_id,public_id,source_proposal_id,created_at,expires_at) "
			+ "values (999,?,888,?,?)", shadowUuid, Timestamp.from(CREATED),
			Timestamp.from(CREATED.plusSeconds(168 * 3600)));
		try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("set search_path to shadow,public");
			statement.execute(Files.readString(Path.of("database/chat-175-migration.sql")));
		}
		assertThat(jdbc.queryForObject("select count(*) from public.chat", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select public_id from shadow.chat_rooms", UUID.class)).isEqualTo(shadowUuid);
		assertThat(jdbc.queryForObject("select to_regclass('shadow.chat')::text", String.class)).isNull();
		jdbc.execute("drop schema shadow cascade");
	}

	@Test
	@DisplayName("동일 물리 연결의 임시 동명 채팅 원본을 보존하며 public 자료만 변경한다")
	void preservesAnImplicitTemporaryChatOnTheSamePhysicalConnection() throws Exception {
		seedLegacyChat();
		UUID temporaryUuid = UUID.randomUUID();
		try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("create temp table chat_rooms (like public.chat_rooms including all)");
			try (PreparedStatement temporary = connection.prepareStatement("insert into pg_temp.chat_rooms "
				+ "(pair_id,public_id,source_proposal_id,created_at,expires_at) values (999,?,888,?,?)")) {
				temporary.setObject(1, temporaryUuid);
				temporary.setTimestamp(2, Timestamp.from(CREATED));
				temporary.setTimestamp(3, Timestamp.from(CREATED.plusSeconds(168 * 3600)));
				temporary.executeUpdate();
			}
			statement.execute(Files.readString(Path.of("database/chat-175-migration.sql")));
			try (var rows = statement.executeQuery("select public_id from pg_temp.chat_rooms")) {
				assertThat(rows.next()).isTrue();
				assertThat(rows.getObject(1, UUID.class)).isEqualTo(temporaryUuid);
			}
			try (var rows = statement.executeQuery("select count(*) from public.chat")) {
				assertThat(rows.next()).isTrue();
				assertThat(rows.getInt(1)).isEqualTo(1);
			}
		}
	}

	private Legacy seedLegacyChat() {
		jdbc.update("insert into matching(id,user_low_id,user_high_id,status,proposed_at,legacy_state) "
			+ "overriding system value values (20,1,2,'ACCEPTED',?,"
			+ "'{\"pair\":{\"pair_id\":10},\"proposal\":{\"proposal_id\":20}}')", Timestamp.from(CREATED));
		UUID roomUuid = UUID.randomUUID();
		Long room = jdbc.queryForObject("insert into chat_rooms(pair_id,public_id,source_proposal_id,created_at,"
			+ "expires_at,last_sequence) values (10,?,20,?,?,3) returning chat_room_id", Long.class, roomUuid,
			Timestamp.from(CREATED), Timestamp.from(CREATED.plusSeconds(168 * 3600)));
		jdbc.update("insert into chat_members(chat_room_id,user_id) values (?,1)", room);
		jdbc.update("insert into chat_members(chat_room_id,user_id,first_notice_acked_at) values (?,2,?)",
			room, Timestamp.from(DISPLAYED));
		message(room, 1, 1, "first");
		message(room, 2, 2, "second");
		long third = message(room, 1, 3, "third");
		jdbc.update("insert into chat_message_reads(message_id,user_id,read_at) values (?,2,?)",
			third, Timestamp.from(DISPLAYED));
		UUID binding = UUID.randomUUID();
		UUID notice = UUID.randomUUID();
		jdbc.update("insert into background_jobs(public_id,job_type,business_key,payload,status,run_at,attempts,"
			+ "lease_until,result_json) values (?,'END_NOTICE',?,cast(? as jsonb),'RUNNING',?,2,?,cast(? as jsonb))",
			notice, "chat-notice:" + room + ":2:END_NOTICE", "{\"roomId\":" + room + ",\"userId\":2}",
			Timestamp.from(DUE), Timestamp.from(DUE.plusSeconds(30)), "{\"" + binding + "\":\"ACCEPTED\"}");
		jdbc.update("insert into background_jobs(public_id,job_type,business_key,payload,status,run_at,finished_at) "
			+ "values (?,'FIRST_ENTRY',?,cast(? as jsonb),'SUCCEEDED',?,?)", UUID.randomUUID(),
			"chat-notice:" + room + ":2:FIRST_ENTRY", "{\"roomId\":" + room + ",\"userId\":2}",
			Timestamp.from(CREATED), Timestamp.from(CREATED));
		return new Legacy(room, roomUuid, third, notice, binding);
	}

	private long message(long room, long sender, long seq, String text) {
		return jdbc.queryForObject("insert into chat_messages(chat_room_id,sender_id,public_id,request_key,"
			+ "request_hash,client_message_id,sequence,body,created_at) values (?,?,?,?,?,?,?,?,?) "
			+ "returning message_id",
			Long.class, room, sender, UUID.randomUUID(), UUID.randomUUID(), ChatText.hash(text), UUID.randomUUID(),
			seq, text, Timestamp.from(CREATED));
	}

	private void migrate() throws Exception {
		try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute(Files.readString(Path.of("database/chat-175-migration.sql")));
		}
	}

	private void assertOriginalTablesRemain() {
		assertThat(jdbc.queryForObject("select count(*) from chat_rooms", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from chat_messages", Integer.class)).isEqualTo(3);
		assertThat(jdbc.queryForObject("select to_regclass('public.chat')::text", String.class)).isNull();
		assertThat(jdbc.queryForObject("select to_regclass('public.chat_participant')::text", String.class)).isNull();
		assertThat(jdbc.queryForObject("select to_regclass('public.chat_message')::text", String.class)).isNull();
	}

	private record Legacy(long roomId, UUID roomUuid, long lastMessage, UUID noticeId, UUID binding) {
	}
}
