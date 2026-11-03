package com.e106.tingle.observability;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.chat.ChatRoom;
import com.e106.tingle.chat.ChatRoomRepository;
import com.e106.tingle.observability.AsyncJobObserver.Sample;
import com.e106.tingle.observability.AsyncJobObserver.Stage;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** 실제 worker를 실행하지 않고 격리 DB의 합성 상태 문서에서 집계·비노출·읽기 전용 경계를 검증한다. */
@SpringBootTest(properties = "app.async-observation.poll-ms=987654321")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class AsyncJobObserverIntegrationTest extends IntegrationTestSupport {
	@Autowired
	private AsyncJobObserver observer;
	@Autowired
	private ProposalRepository proposals;
	@Autowired
	private MatchingParticipantRepository participants;
	@Autowired
	private ChatRoomRepository rooms;
	private long matching;
	private long room;
	private long owner;

	@BeforeEach
	void prepareOnlyOwnedSyntheticRows() throws Exception {
		long first = userIdOf(login("google", newSocialId()));
		long second = userIdOf(login("google", newSocialId()));
		owner = Math.min(first, second);
		matching = proposals.saveAndFlush(MatchProposal.proposed(first, second, clock.instant())).getId();
		participants.saveAllAndFlush(List.of(new MatchingParticipant(matching, first),
			new MatchingParticipant(matching, second)));
		room = rooms.saveAndFlush(new ChatRoom(matching, matching, clock.instant())).getId();
	}

	@AfterEach
	void removeOnlyThisTestsSyntheticJobRows() {
		jdbc.update("delete from chat where id=?", room);
		jdbc.update("delete from matching_participant where matching_id=?", matching);
		jdbc.update("delete from matching where id=?", matching);
	}

	@Test
	void sixStagesDistinguishRetriesFailuresFallbackAndOverdueWithoutChangingTheDocuments() throws Exception {
		workflow(toJson(Map.of("state", "READY", "attempts", 0)));
		jdbc.update("update matching set evaluation=cast(? as jsonb),chat_deletion_state=cast(? as jsonb),"
			+ "chat_external_cleanup_state=cast(? as jsonb) where id=?",
			toJson(Map.of("matchExplanations", Map.of("viewers", Map.of("7", Map.of("current",
				Map.of("status", "SUCCEEDED", "source", "RULE_FALLBACK", "text", "private-body")))))),
			toJson(Map.of("status", "RETRY", "attempt", 2, "nextAttemptAt",
				clock.instant().minusSeconds(20).toString())), toJson(Map.of("status", "BLOCKED", "attempt", 0)),
			matching);
		jdbc.update("update chat set icebreakers=cast(? as jsonb),ai_state=cast(? as jsonb) where id=?",
			toJson(Map.of("status", "PENDING", "attempts", 2, "nextRetryAt",
				clock.instant().minusSeconds(30).toString(), "questions", List.of("private-body"))),
			toJson(Map.of("riskAnalysis", Map.of("status", "FAILED", "attempts", 3))), room);
		Map<String, Object> before = jdbc.queryForMap("select * from matching where id=?", matching);
		List<Sample> result = observer.snapshot();
		assertThat(result).hasSize(6);
		assertThat(sample(result, Stage.MATCHING).missingDue()).isEqualTo(1);
		assertThat(sample(result, Stage.EXPLANATION).fallback()).isEqualTo(1);
		assertThat(sample(result, Stage.RISK).failed()).isEqualTo(1);
		assertThat(sample(result, Stage.RISK).fallback()).isZero();
		assertThat(sample(result, Stage.EXTERNAL_CLEANUP).blocked()).isEqualTo(1);
		for (Stage stage : List.of(Stage.ICEBREAKER, Stage.DB_DELETION)) {
			assertThat(sample(result, stage).retrying()).isEqualTo(1);
			assertThat(sample(result, stage).overdue()).isEqualTo(1);
		}
		assertThat(sample(result, Stage.ICEBREAKER).maxDelaySeconds()).isGreaterThanOrEqualTo(30);
		assertThat(jdbc.queryForMap("select * from matching where id=?", matching)).isEqualTo(before);
		Logger logger = (Logger)LoggerFactory.getLogger(AsyncJobObserver.class);
		ListAppender<ILoggingEvent> capture = new ListAppender<>();
		capture.start();
		logger.addAppender(capture);
		try {
			observer.publish();
			assertThat(capture.list).hasSize(6).allSatisfy(event -> {
				assertThat(event.getFormattedMessage()).startsWith("event=async_jobs stage=")
					.doesNotContain("private-body", "questions", "receipt", "user_id", "select ");
			});
		} finally {
			logger.detachAppender(capture);
			capture.stop();
		}
	}

	@Test
	void malformedStateAndDueTimeAreVisibleWithoutParsingPrivateDocumentsInTheApplication() throws Exception {
		workflow("broken-json-private-body");
		jdbc.update("update chat set icebreakers=cast(? as jsonb) where id=?",
			toJson(Map.of("status", "PENDING", "deadlineAt", "not-a-time")), room);
		List<Sample> result = observer.snapshot();
		assertThat(sample(result, Stage.MATCHING).invalid()).isEqualTo(1);
		assertThat(sample(result, Stage.ICEBREAKER).invalid()).isEqualTo(1);
		assertThat(sample(result, Stage.ICEBREAKER).missingDue()).isEqualTo(1);
		assertThat(sample(result, Stage.ICEBREAKER).overdue()).isZero();
	}

	@Test
	void emptyStagesEmitZeroHeartbeatWithoutInventingACompletedJob() {
		assertThat(observer.snapshot()).hasSize(6).allSatisfy(sample -> {
			assertThat(sample.total()).isZero();
			assertThat(sample.failed()).isZero();
			assertThat(sample.fallback()).isZero();
		});
	}

	private void workflow(String value) {
		jdbc.update("update matching_participant set request_state=jsonb_build_object("
			+ "'encounter:workflow',cast(? as text)) where matching_id=? and user_id=?", value, matching, owner);
	}

	private Sample sample(List<Sample> values, Stage stage) {
		return values.stream().filter(value -> value.stage() == stage).findFirst().orElseThrow();
	}
}
