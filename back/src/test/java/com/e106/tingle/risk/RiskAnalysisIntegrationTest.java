package com.e106.tingle.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.global.ai.AiCallException;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.risk.RiskAnalysisTypes.Audience;
import com.e106.tingle.risk.RiskAnalysisTypes.Category;
import com.e106.tingle.risk.RiskAnalysisTypes.Finding;
import com.e106.tingle.risk.RiskAnalysisTypes.Input;
import com.e106.tingle.risk.RiskAnalysisTypes.Kind;
import com.e106.tingle.risk.RiskAnalysisTypes.Manifest;
import com.e106.tingle.risk.RiskAnalysisTypes.Message;
import com.e106.tingle.risk.RiskAnalysisTypes.NoticeType;
import com.e106.tingle.risk.RiskAnalysisTypes.Request;
import com.e106.tingle.risk.RiskAnalysisTypes.Response;
import com.e106.tingle.risk.RiskAnalysisTypes.Verdict;

import tools.jackson.databind.JsonNode;

/** Real PostgreSQL orchestration with synthetic messages and a non-network model/tokenizer test double. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class RiskAnalysisIntegrationTest extends IntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-10-29T04:00:00Z");
	private static final String SECRET_SENTENCE = "가상 대화: 송금 전에 설명을 확인해요";

	@Autowired
	private RiskAnalysisService service;
	@Autowired
	private RiskAnalysisStore store;
	@Autowired
	private Clock testClock;
	@MockitoBean
	private RiskAnalysisGateway gateway;
	@MockitoBean
	private RiskManifestCatalog manifests;

	@BeforeEach
	void syntheticModelAndClock() {
		given(testClock.instant()).willReturn(NOW);
		given(manifests.supports(any())).willReturn(true);
		// The synthetic tokenizer is independent of character count; it makes no provider-tokenizer claim.
		given(gateway.countTokens(any())).willReturn(100);
		given(gateway.analyze(any(), any())).willReturn(normal());
	}

	@Test
	void normalAnalysisUsesMaskedAliasesAndPersistsNoConversationCopy() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE + " https://synthetic.example/path");
		jdbc.update("update chat set ai_state='{\"legacyPreserved\":true}' where id=?", room.id());
		Request request = request(room);
		Response response = service.analyze(request);
		assertThat(response.status()).isEqualTo("SUCCEEDED");
		assertThat(response.attemptId()).isEqualTo(request.attemptId());
		assertThat(response.categories()).isEmpty();
		ArgumentCaptor<Input> input = ArgumentCaptor.forClass(Input.class);
		verify(gateway).analyze(any(), input.capture());
		assertThat(input.getValue().messages()).hasSize(1);
		Message sent = input.getValue().messages().getFirst();
		assertThat(sent.speaker()).isIn("A", "B");
		assertThat(sent.fresh()).isTrue();
		assertThat(sent.text()).doesNotContain("https://", "synthetic.example");
		assertThat(state(room)).contains("legacyPreserved", "NORMAL").doesNotContain(SECRET_SENTENCE);
		assertThat(notices(room)).isZero();
	}

	@Test
	void harassmentForBothIsStoredAsSenderAndRecipientRows() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		given(gateway.analyze(any(), any())).willAnswer(invocation -> harassment(invocation.getArgument(1)));
		service.analyze(request(room));
		// BOTH 판정은 두 줄로 나뉘어 각자 역할이 남는다. 앱은 BOTH 를 받지 않는다
		assertThat(jdbc.queryForList("select n.chat_participant_id::text || ':' || n.audience "
			+ "from chat_safety_notice n join chat_participant p on p.id=n.chat_participant_id where p.chat_id=?",
			String.class, room.id()))
			.containsExactlyInAnyOrder(room.firstParticipant() + ":SENDER", room.secondParticipant() + ":RECIPIENT");
	}

	@Test
	void harassmentExchangedBothWaysGivesEachPersonBothNotices() throws Exception {
		Fixture room = room();
		given(gateway.analyze(any(), any())).willAnswer(invocation -> harassment(invocation.getArgument(1)));
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		service.analyze(request(room));
		message(room, 2, room.secondParticipant(), "맞받은 가상 욕설");
		service.analyze(request(room));
		// 보낸 쪽 안내와 받은 쪽 안내는 서로 다른 안내라 한 사람이 각각 한 번씩 받는다
		assertThat(jdbc.queryForList("select n.chat_participant_id::text || ':' || n.audience "
			+ "from chat_safety_notice n join chat_participant p on p.id=n.chat_participant_id where p.chat_id=?",
			String.class, room.id()))
			.containsExactlyInAnyOrder(room.firstParticipant() + ":SENDER", room.secondParticipant() + ":RECIPIENT",
				room.secondParticipant() + ":SENDER", room.firstParticipant() + ":RECIPIENT");
		// 같은 방향이 되풀이되면 더 늘지 않는다
		message(room, 3, room.firstParticipant(), "되풀이한 가상 욕설");
		service.analyze(request(room));
		assertThat(notices(room)).isEqualTo(4);
	}

	@Test
	void noticeIsStoredWithoutErrorBeforeTheIndexIsReplaced() throws Exception {
		// 새 버전을 먼저 배포하고 인덱스를 나중에 바꾸는 동안(옛 2칸 인덱스)에도 저장이 실패하지 않는다
		// 다른 테스트가 남긴 줄(같은 사람·같은 유형 두 줄)이 있으면 옛 인덱스를 만들 수 없다
		jdbc.update("delete from chat_safety_notice");
		dropNoticeUniqueness();
		jdbc.execute("create unique index uq_chat_safety_notice_once "
			+ "on chat_safety_notice(chat_participant_id,notice_type)");
		try {
			Fixture room = room();
			given(gateway.analyze(any(), any())).willAnswer(invocation -> harassment(invocation.getArgument(1)));
			message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
			service.analyze(request(room));
			message(room, 2, room.secondParticipant(), "맞받은 가상 욕설");
			service.analyze(request(room));
			// 옛 인덱스에서는 예전처럼 같은 사람·같은 유형 한 번만 남는다
			assertThat(notices(room)).isEqualTo(2);
		} finally {
			dropNoticeUniqueness();
			jdbc.execute("create unique index uq_chat_safety_notice_once "
				+ "on chat_safety_notice(chat_participant_id,notice_type,audience)");
		}
	}

	@Test
	void riskMappingIsRecipientSpecificAndDuplicateAttemptsNeverCallTheModelTwice() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		given(gateway.analyze(any(), any())).willAnswer(invocation -> money(invocation.getArgument(1)));
		Request request = request(room);
		Response first = service.analyze(request);
		assertThat(service.analyze(request)).isEqualTo(first);
		assertThat(first.categories()).containsExactly("FRAUD");
		assertThat(notices(room)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select n.chat_participant_id from chat_safety_notice n "
			+ "join chat_participant p on p.id=n.chat_participant_id where p.chat_id=?", Long.class, room.id()))
			.isEqualTo(room.secondParticipant());
		// 받은 사람 안내로 남는다(앱이 보호 문구·차단 버튼을 고른다)
		assertThat(jdbc.queryForObject("select n.audience from chat_safety_notice n "
			+ "join chat_participant p on p.id=n.chat_participant_id where p.chat_id=?", String.class, room.id()))
			.isEqualTo("RECIPIENT");
		verify(gateway, times(1)).analyze(any(), any());
		message(room, 2, room.firstParticipant(), "추가 가상 송금 요구");
		service.analyze(request(room));
		assertThat(notices(room)).isEqualTo(1);
		verify(gateway, times(2)).analyze(any(), any());
	}

	@Test
	void attemptCannotBeReboundToAnotherManifest() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		Request first = request(room);
		service.analyze(first);
		assertCode(() -> service.analyze(new Request(first.attemptId(), room.id(), manifest("synthetic-v2"))),
			ErrorCode.IDEMPOTENCY_CONFLICT);
		verify(gateway, times(1)).analyze(any(), any());
	}

	@Test
	void unsupportedManifestDoesNotCreateAnAttemptOrReadMessagesIntoTheGateway() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		String original = state(room);
		given(manifests.supports(any())).willReturn(false);
		assertCode(() -> service.analyze(request(room)), ErrorCode.POLICY_UNRESOLVED);
		assertThat(state(room)).isEqualTo(original);
		verify(gateway, never()).countTokens(any());
		verify(gateway, never()).analyze(any(), any());
	}

	@Test
	void bothCurrentConsentsAndAnActiveRoomAreRequiredBeforeModelInvocation() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		jdbc.update("delete from user_consents where user_id=? and consent_type='AI_RISK_DETECTION'", room.second());
		assertCode(() -> service.analyze(request(room)), ErrorCode.AI_RISK_CONSENT_REQUIRED);
		consent(room.second(), false, AI_RISK_VERSION, NOW.minusSeconds(61));
		assertCode(() -> service.analyze(request(room)), ErrorCode.AI_RISK_CONSENT_REQUIRED);
		consent(room.second(), true, "obsolete");
		assertCode(() -> service.analyze(request(room)), ErrorCode.AI_RISK_CONSENT_REQUIRED);
		consent(room.second(), true, AI_RISK_VERSION);
		jdbc.update("update chat set closed_at=?,close_reason='LEFT' where id=?", stamp(NOW), room.id());
		assertCode(() -> service.analyze(request(room)), ErrorCode.CHAT_CLOSED);
		assertCode(() -> service.analyze(new Request(UUID.randomUUID(), Long.MAX_VALUE, manifest("synthetic-v1"))),
			ErrorCode.RESOURCE_NOT_FOUND);
		verify(gateway, never()).analyze(any(), any());
		assertThat(notices(room)).isZero();
	}

	@Test
	void anotherAttemptCannotStealAClaimWhileTheModelIsRunning() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		given(gateway.analyze(any(), any())).willAnswer(invocation -> {
			entered.countDown();
			await(release);
			return normal();
		});
		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			Future<Response> running = executor.submit(() -> service.analyze(request(room)));
			try {
				assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
				assertCode(() -> service.analyze(request(room)), ErrorCode.SERVICE_UNAVAILABLE);
			} finally {
				release.countDown();
			}
			assertThat(running.get(15, TimeUnit.SECONDS).status()).isEqualTo("SUCCEEDED");
		}
		verify(gateway, times(1)).analyze(any(), any());
	}

	@Test
	void expiredUnfinishedClaimCanResumeWithTheSameAttemptUuid() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		Request request = request(room);
		store.claim(request);
		verify(gateway, never()).analyze(any(), any());
		given(testClock.instant()).willReturn(NOW.plusSeconds(31));
		Response response = service.analyze(request);
		assertThat(response.status()).isEqualTo("SUCCEEDED");
		assertThat(service.analyze(request)).isEqualTo(response);
		verify(gateway, times(1)).analyze(any(), any());
		assertThat(jsonMapper.readTree(state(room)).path("riskAnalysis").path("lastAnalyzedSeq").asLong())
			.isEqualTo(1);
	}

	@Test
	void lateCompletionOfReplacedAttemptReplaysFailureWithoutChangingItsSuccessor() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		Request first = request(room);
		RiskAnalysisStore.Claim abandoned = store.claim(first);
		given(testClock.instant()).willReturn(NOW.plusSeconds(31));
		Request second = request(room);
		RiskAnalysisStore.Claim successor = store.claim(second);
		JsonNode running = jsonMapper.readTree(state(room)).path("riskAnalysis");

		Response discarded = store.finish(abandoned, money(abandoned.input()));
		assertThat(discarded.status()).isEqualTo("FAILED");
		JsonNode after = jsonMapper.readTree(state(room)).path("riskAnalysis");
		for (String field : List.of("generation", "attemptId", "status", "throughSeq", "lastAnalyzedSeq",
			"attempts", "startedAt", "firstStartedAt", "signature", "consentLow", "consentHigh", "kind", "retryAt")) {
			assertThat(after.path(field)).as(field).isEqualTo(running.path(field));
		}
		assertThat(notices(room)).isZero();
		assertThat(after.path("lastAnalyzedSeq").asLong()).isZero();
		RiskAnalysisStore.Claim replay = store.claim(first);
		assertThat(replay.cached()).isEqualTo(discarded);
		assertThat(replay.input()).isNull();
		assertThat(service.analyze(first)).isEqualTo(discarded);

		Response succeeded = store.finish(successor, money(successor.input()));
		assertThat(succeeded.status()).isEqualTo("SUCCEEDED");
		assertThat(succeeded.categories()).containsExactly("FRAUD");
		assertThat(service.analyze(second)).isEqualTo(succeeded);
		assertThat(service.analyze(first)).isEqualTo(discarded);
		assertThat(notices(room)).isEqualTo(1);
		assertThat(jsonMapper.readTree(state(room)).path("riskAnalysis").path("lastAnalyzedSeq").asLong())
			.isEqualTo(1);
		String completed = state(room);
		store.finish(abandoned, normal());
		assertThat(state(room)).isEqualTo(completed);
		verify(gateway, never()).analyze(any(), any());
	}

	@Test
	void lateCompletionCannotReplaceTheReclaimedSameUuidOrItsFinalReceipt() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		Request request = request(room);
		RiskAnalysisStore.Claim abandoned = store.claim(request);
		given(testClock.instant()).willReturn(NOW.plusSeconds(31));
		RiskAnalysisStore.Claim reclaimed = store.claim(request);
		String running = state(room);
		assertThat(store.finish(abandoned, money(abandoned.input())).status()).isEqualTo("FAILED");
		assertThat(state(room)).isEqualTo(running);
		assertCode(() -> store.claim(request), ErrorCode.SERVICE_UNAVAILABLE);
		assertThat(notices(room)).isZero();

		Response succeeded = store.finish(reclaimed, money(reclaimed.input()));
		assertThat(succeeded.status()).isEqualTo("SUCCEEDED");
		String completed = state(room);
		store.finish(abandoned, normal());
		assertThat(state(room)).isEqualTo(completed);
		assertThat(service.analyze(request)).isEqualTo(succeeded);
		assertThat(notices(room)).isEqualTo(1);
		assertThat(jsonMapper.readTree(state(room)).path("riskAnalysis").path("lastAnalyzedSeq").asLong())
			.isEqualTo(1);
		verify(gateway, never()).analyze(any(), any());
	}

	@Test
	void withdrawalAndRegrantDuringAnalysisCannotMakeTheOldResultCurrentAgain() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		given(gateway.analyze(any(), any())).willAnswer(invocation -> {
			// Explicit history fixture; this is not a claim that the separate withdrawal API was exercised.
			consent(room.second(), false, AI_RISK_VERSION);
			consent(room.second(), true, AI_RISK_VERSION);
			return money(invocation.getArgument(1));
		});
		assertThat(service.analyze(request(room)).status()).isEqualTo("FAILED");
		assertThat(notices(room)).isZero();
		assertCode(() -> service.analyze(request(room)), ErrorCode.CHAT_CLOSED);
		verify(gateway, times(1)).analyze(any(), any());
		assertThat(jsonMapper.readTree(state(room)).path("riskAnalysis").path("lastAnalyzedSeq").asLong()).isZero();
	}

	@Test
	void closureWhileWaitingForTheModelRejectsItsLateResultWithoutCreatingNotices() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		given(gateway.analyze(any(), any())).willAnswer(invocation -> {
			jdbc.update("update chat set closed_at=?,close_reason='LEFT' where id=?", stamp(NOW), room.id());
			return money(invocation.getArgument(1));
		});
		assertThat(service.analyze(request(room)).status()).isEqualTo("FAILED");
		assertThat(notices(room)).isZero();
		assertThat(jdbc.queryForObject("select closed_at from chat where id=?", Timestamp.class, room.id()))
			.isEqualTo(stamp(NOW));
	}

	@Test
	void messageMutationWhileTheModelIsRunningDiscardsItsResult() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		Request request = request(room);
		given(gateway.analyze(any(), any())).willAnswer(invocation -> {
			jdbc.update("update chat_message set body=? where chat_id=? and seq=1", "변경된 가상 대화", room.id());
			return money(invocation.getArgument(1));
		});
		Response response = service.analyze(request);
		assertRecordedFailureReplay(room, request, response, NOW);
	}

	@Test
	void messageDeletionWhileTheModelIsRunningDiscardsItsResult() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		Request request = request(room);
		given(gateway.analyze(any(), any())).willAnswer(invocation -> {
			jdbc.update("delete from chat_message where chat_id=? and seq=1", room.id());
			return money(invocation.getArgument(1));
		});
		Response response = service.analyze(request);
		given(testClock.instant()).willReturn(NOW.plusSeconds(31));
		assertRecordedFailureReplay(room, request, response, NOW);
	}

	@Test
	void analysisFinishingExactlyAtTheBudgetDeadlineReplaysItsFailure() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		Request request = request(room);
		Instant deadline = NOW.plus(Duration.ofMinutes(5));
		given(gateway.analyze(any(), any())).willAnswer(invocation -> {
			given(testClock.instant()).willReturn(deadline);
			return money(invocation.getArgument(1));
		});
		Response response = service.analyze(request);
		assertRecordedFailureReplay(room, request, response, deadline);
		assertCode(() -> service.analyze(request(room)), ErrorCode.SERVICE_UNAVAILABLE);
	}

	@Test
	void failuresHaveARealBackoffAndCountTheInitialCallTowardThreeAttempts() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		given(gateway.analyze(any(), any())).willReturn(failed());
		assertThat(service.analyze(request(room)).status()).isEqualTo("FAILED");
		assertCode(() -> service.analyze(request(room)), ErrorCode.SERVICE_UNAVAILABLE);
		given(testClock.instant()).willReturn(NOW.plusSeconds(2));
		assertThat(service.analyze(request(room)).status()).isEqualTo("FAILED");
		given(testClock.instant()).willReturn(NOW.plusSeconds(4));
		assertThat(service.analyze(request(room)).status()).isEqualTo("FAILED");
		given(testClock.instant()).willReturn(NOW.plusSeconds(6));
		assertCode(() -> service.analyze(request(room)), ErrorCode.SERVICE_UNAVAILABLE);
		verify(gateway, times(3)).analyze(any(), any());
		assertThat(notices(room)).isZero();
	}

	@Test
	void theFiveMinuteBudgetCannotBeRestartedByGivingTheRetryAnotherUuid() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), SECRET_SENTENCE);
		given(gateway.analyze(any(), any())).willReturn(failed());
		service.analyze(request(room));
		given(testClock.instant()).willReturn(NOW.plus(Duration.ofMinutes(5)));
		assertCode(() -> service.analyze(request(room)), ErrorCode.SERVICE_UNAVAILABLE);
		verify(gateway, times(1)).analyze(any(), any());
	}

	@Test
	void messageLimitConsumesTheOldestUnanalysedPrefixInsteadOfSkippingToTheLatestTen() throws Exception {
		Fixture room = room();
		for (int sequence = 1; sequence <= 12; sequence++) {
			message(room, sequence, room.firstParticipant(), "가상 메시지 " + sequence);
		}
		service.analyze(request(room));
		service.analyze(request(room));
		ArgumentCaptor<Input> inputs = ArgumentCaptor.forClass(Input.class);
		verify(gateway, times(2)).analyze(any(), inputs.capture());
		assertThat(inputs.getAllValues()).allSatisfy(input ->
			assertThat(input.messages()).hasSizeLessThanOrEqualTo(10));
		assertThat(inputs.getAllValues().stream().flatMap(input -> input.messages().stream())
			.filter(Message::fresh).map(Message::sequence)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L,
				7L, 8L, 9L, 10L, 11L, 12L);
	}

	@Test
	void modelTokenizerLimitSplitsFreshMessagesWithoutAdvancingPastUnsentMessages() throws Exception {
		Fixture room = room();
		for (int sequence = 1; sequence <= 4; sequence++) {
			message(room, sequence, room.firstParticipant(), "짧은 가상 메시지 " + sequence);
		}
		given(gateway.countTokens(any())).willAnswer(invocation -> {
			Input input = invocation.getArgument(0);
			return input.messages().size() * 1200;
		});
		service.analyze(request(room));
		service.analyze(request(room));
		ArgumentCaptor<Input> inputs = ArgumentCaptor.forClass(Input.class);
		verify(gateway, times(2)).analyze(any(), inputs.capture());
		assertThat(inputs.getAllValues()).allSatisfy(input ->
			assertThat(input.messages()).hasSizeLessThanOrEqualTo(2));
		assertThat(inputs.getAllValues().stream().flatMap(input -> input.messages().stream())
			.filter(Message::fresh).map(Message::sequence)).containsExactly(1L, 2L, 3L, 4L);
	}

	@Test
	void automaticRetryPinsInputManifestAndDeadlineDespiteNewMessages() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "첫 가상 메시지");
		given(gateway.analyze(any(), any(), any(Duration.class))).willReturn(failed());
		Manifest manifest = manifest("synthetic-v1");
		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		JsonNode first = jsonMapper.readTree(state(room)).path("riskAnalysis");
		message(room, 2, room.secondParticipant(), "재시도 중 도착한 가상 메시지");
		given(testClock.instant()).willReturn(NOW.plusSeconds(1));
		assertThat(service.analyzeNext(room.id(), manifest("synthetic-v2"))).isFalse();
		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		JsonNode second = jsonMapper.readTree(state(room)).path("riskAnalysis");
		assertThat(second.path("firstStartedAt")).isEqualTo(first.path("firstStartedAt"));
		assertThat(second.path("throughSeq").asLong()).isEqualTo(1);
		assertThat(second.path("manifest")).isEqualTo(first.path("manifest"));
		assertThat(second.path("attemptId")).isNotEqualTo(first.path("attemptId"));
		given(testClock.instant()).willReturn(NOW.plusSeconds(2));
		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		given(testClock.instant()).willReturn(NOW.plusSeconds(3));
		assertThat(service.analyzeNext(room.id(), manifest)).isFalse();
		JsonNode terminal = jsonMapper.readTree(state(room)).path("riskAnalysis");
		assertThat(terminal.path("terminal").asBoolean()).isTrue();
		assertThat(terminal.path("attempts").asInt()).isEqualTo(3);
		assertThat(terminal.path("receipts").size()).isEqualTo(3);
		assertThat(terminal.path("lastAnalyzedSeq").asLong()).isZero();
		ArgumentCaptor<Input> sent = ArgumentCaptor.forClass(Input.class);
		verify(gateway, times(3)).analyze(any(), sent.capture(), any(Duration.class));
		assertThat(sent.getAllValues()).allSatisfy(input -> {
			assertThat(input.manifest()).isEqualTo(manifest);
			assertThat(input.messages()).extracting(Message::sequence).containsExactly(1L);
		});
		String failedAttempt = terminal.path("attemptId").asString();
		JsonNode failedReceipt = terminal.path("receipts").path(failedAttempt);
		JsonNode range = terminal.path("terminalRanges").path(failedAttempt);
		assertThat(range.path("fromSeq").asLong()).isEqualTo(1);
		assertThat(range.path("throughSeq").asLong()).isEqualTo(1);
		assertThat(range.path("kind").asString()).isEqualTo("FAILED");
		assertThat(terminal.path("processedThroughSeq").asLong()).isEqualTo(1);
		given(gateway.analyze(any(), any(), any(Duration.class))).willReturn(normal());
		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		JsonNode successor = jsonMapper.readTree(state(room)).path("riskAnalysis");
		assertThat(successor.path("lastAnalyzedSeq").asLong()).isEqualTo(2);
		assertThat(successor.path("terminalRanges").path(failedAttempt)).isEqualTo(range);
		assertThat(successor.path("receipts").path(failedAttempt)).isEqualTo(failedReceipt);
		ArgumentCaptor<Input> allSent = ArgumentCaptor.forClass(Input.class);
		verify(gateway, times(4)).analyze(any(), allSent.capture(), any(Duration.class));
		Input later = allSent.getAllValues().getLast();
		assertThat(later.messages().stream().filter(Message::fresh).map(Message::sequence)).containsExactly(2L);
		assertThat(later.messages().stream().filter(message -> !message.fresh()).map(Message::sequence))
			.containsExactly(1L);
		assertThat(service.analyzeNext(room.id(), manifest)).isFalse();
	}

	@Test
	void automaticSuccessAdvancesOnlyTheSuccessfullyAnalyzedPrefix() throws Exception {
		Fixture room = room();
		for (int seq = 1; seq <= 12; seq++) {
			message(room, seq, room.firstParticipant(), "가상 메시지 " + seq);
		}
		given(gateway.analyze(any(), any(), any(Duration.class))).willReturn(normal());
		assertThat(service.analyzeNext(room.id(), manifest("synthetic-v1"))).isTrue();
		assertThat(service.analyzeNext(room.id(), manifest("synthetic-v1"))).isTrue();
		assertThat(jsonMapper.readTree(state(room)).path("riskAnalysis").path("lastAnalyzedSeq").asLong())
			.isEqualTo(12);
		ArgumentCaptor<Input> sent = ArgumentCaptor.forClass(Input.class);
		verify(gateway, times(2)).analyze(any(), sent.capture(), any(Duration.class));
		assertThat(sent.getAllValues().stream().flatMap(input -> input.messages().stream())
			.filter(Message::fresh).map(Message::sequence)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L,
				7L, 8L, 9L, 10L, 11L, 12L);
	}

	@Test
	void automaticLeaseRecoveryKeepsAttemptAndDeadlineAndRejectsTheOldGeneration() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "가상 메시지");
		Manifest manifest = manifest("synthetic-v1");
		RiskAnalysisStore.Claim first = store.claimNext(room.id(), manifest);
		assertThat(store.claimNext(room.id(), manifest)).isNull();
		given(testClock.instant()).willReturn(NOW.plusSeconds(31));
		RiskAnalysisStore.Claim recovered = store.claimNext(room.id(), manifest);
		assertThat(recovered.request().attemptId()).isEqualTo(first.request().attemptId());
		assertThat(recovered.deadlineAt()).isEqualTo(first.deadlineAt());
		assertThat(recovered.generation()).isGreaterThan(first.generation());
		assertThat(store.canTransmit(first)).isFalse();
		assertThat(store.canTransmit(recovered)).isTrue();
		assertThat(store.finish(first, normal()).status()).isEqualTo("FAILED");
		assertThat(store.finish(recovered, normal()).status()).isEqualTo("SUCCEEDED");
		assertThat(jsonMapper.readTree(state(room)).path("riskAnalysis").path("attempts").asInt()).isEqualTo(2);
	}

	@Test
	void automaticDeadlineFinalizesTheFailedRangeWithoutReprocessingIt() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "가상 메시지");
		RiskAnalysisStore.Claim first = store.claimNext(room.id(), manifest("synthetic-v1"));
		given(testClock.instant()).willReturn(first.deadlineAt());
		assertThat(store.canTransmit(first)).isFalse();
		assertThat(service.analyzeNext(room.id(), manifest("synthetic-v1"))).isFalse();
		JsonNode failed = jsonMapper.readTree(state(room)).path("riskAnalysis");
		assertThat(failed.path("terminal").asBoolean()).isTrue();
		assertThat(failed.path("receipts").path(first.request().attemptId().toString()).has("result")).isTrue();
		assertThat(failed.path("processedThroughSeq").asLong()).isEqualTo(1);
		assertThat(failed.path("lastAnalyzedSeq").asLong()).isZero();
		assertThat(store.candidates(room.id() - 1, 20)).doesNotContain(room.id());
		assertThat(service.analyzeNext(room.id(), manifest("synthetic-v1"))).isFalse();
		verify(gateway, never()).analyze(any(), any(), any(Duration.class));
	}

	@Test
	void automaticSkipsAMessageThatExceedsTheLimitAloneAndAnalyzesTheNextOne() throws Exception {
		Fixture room = room();
		// 채팅은 600글자로 허용하지만 분석 입력에서는 4,200 코드 포인트다.
		String family = "👨‍👩‍👧‍👦";
		message(room, 1, room.firstParticipant(), family.repeat(600));
		message(room, 2, room.secondParticipant(), "뒤에 도착한 가상 메시지");
		given(gateway.analyze(any(), any(), any(Duration.class))).willReturn(normal());
		Manifest manifest = manifest("synthetic-v1");

		assertThat(service.analyzeNext(room.id(), manifest)).isFalse();
		JsonNode skipped = jsonMapper.readTree(state(room)).path("riskAnalysis");
		String attempt = skipped.path("attemptId").asString();
		assertThat(skipped.path("terminalRanges").path(attempt).path("fromSeq").asLong()).isEqualTo(1);
		assertThat(skipped.path("terminalRanges").path(attempt).path("throughSeq").asLong()).isEqualTo(1);
		assertThat(skipped.path("terminalRanges").path(attempt).path("kind").asString()).isEqualTo("FAILED");
		assertThat(skipped.path("processedThroughSeq").asLong()).isEqualTo(1);
		assertThat(skipped.path("lastAnalyzedSeq").asLong()).isZero();
		verify(gateway, never()).analyze(any(), any(), any(Duration.class));

		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		ArgumentCaptor<Input> sent = ArgumentCaptor.forClass(Input.class);
		verify(gateway).analyze(any(), sent.capture(), any(Duration.class));
		assertThat(sent.getValue().messages()).extracting(Message::sequence).containsExactly(2L);
		JsonNode next = jsonMapper.readTree(state(room)).path("riskAnalysis");
		assertThat(next.path("lastAnalyzedSeq").asLong()).isEqualTo(2);
		assertThat(next.path("terminalRanges").path(attempt))
			.isEqualTo(skipped.path("terminalRanges").path(attempt));
		assertThat(store.candidates(room.id() - 1, 20)).doesNotContain(room.id());
	}

	@Test
	void automaticInputRejectedByTheAiServerIsFinalizedWithoutRetries() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "AI가 거절하는 가상 메시지");
		given(gateway.analyze(any(), any(), any(Duration.class))).willThrow(new AiCallException(false, 400));
		Manifest manifest = manifest("synthetic-v1");

		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		JsonNode rejected = jsonMapper.readTree(state(room)).path("riskAnalysis");
		String attempt = rejected.path("attemptId").asString();
		assertThat(rejected.path("terminal").asBoolean()).isTrue();
		assertThat(rejected.path("terminalRanges").path(attempt).path("throughSeq").asLong()).isEqualTo(1);
		assertThat(rejected.path("processedThroughSeq").asLong()).isEqualTo(1);
		assertThat(store.candidates(room.id() - 1, 20)).doesNotContain(room.id());

		message(room, 2, room.secondParticipant(), "뒤에 도착한 가상 메시지");
		given(gateway.analyze(any(), any(), any(Duration.class))).willReturn(normal());
		given(testClock.instant()).willReturn(NOW.plusSeconds(1));
		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		ArgumentCaptor<Input> sent = ArgumentCaptor.forClass(Input.class);
		verify(gateway, times(2)).analyze(any(), sent.capture(), any(Duration.class));
		assertThat(sent.getAllValues().getLast().messages().stream().filter(Message::fresh).map(Message::sequence))
			.containsExactly(2L);
	}

	@Test
	void automaticTransientAiFailureIsStillRetried() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "가상 메시지");
		given(gateway.analyze(any(), any(), any(Duration.class))).willThrow(new AiCallException(true, 503));
		Manifest manifest = manifest("synthetic-v1");
		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		JsonNode failed = jsonMapper.readTree(state(room)).path("riskAnalysis");
		assertThat(failed.path("terminal").asBoolean()).isFalse();
		assertThat(failed.path("processedThroughSeq").asLong()).isZero();
		given(testClock.instant()).willReturn(NOW.plusSeconds(1));
		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		verify(gateway, times(2)).analyze(any(), any(), any(Duration.class));
	}

	@Test
	void automaticPolicyRejectionFromTheAiServerIsAlsoFinalizedWithoutRetries() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "정책 미확정 가상 메시지");
		given(gateway.analyze(any(), any(), any(Duration.class))).willThrow(new AiCallException(false, 422));
		assertThat(service.analyzeNext(room.id(), manifest("synthetic-v1"))).isTrue();
		JsonNode rejected = jsonMapper.readTree(state(room)).path("riskAnalysis");
		assertThat(rejected.path("terminal").asBoolean()).isTrue();
		assertThat(rejected.path("processedThroughSeq").asLong()).isEqualTo(1);
		given(testClock.instant()).willReturn(NOW.plusSeconds(2));
		assertThat(service.analyzeNext(room.id(), manifest("synthetic-v1"))).isFalse();
		verify(gateway, times(1)).analyze(any(), any(), any(Duration.class));
	}

	@Test
	void batchPinnedUnderOlderInputRulesIsReanalyzedInsteadOfClosedAsMutated() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "배포 전에 고정된 가상 메시지");
		message(room, 2, room.secondParticipant(), "그 뒤 위협 가상 메시지");
		Manifest manifest = manifest("synthetic-v1");
		given(gateway.analyze(any(), any(), any(Duration.class))).willThrow(new AiCallException(true, 503));
		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		// 이전 버전이 남긴 상태처럼: 규칙 표시가 없고 해시가 이전 마스킹 결과다.
		jdbc.update("update chat set ai_state=jsonb_set(ai_state #- '{riskAnalysis,inputRules}', "
			+ "'{riskAnalysis,inputHash}', '\"old-masking-hash\"') where id=?", room.id());

		given(gateway.analyze(any(), any(), any(Duration.class))).willReturn(normal());
		given(testClock.instant()).willReturn(NOW.plusSeconds(2));
		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		JsonNode state = jsonMapper.readTree(state(room)).path("riskAnalysis");
		assertThat(state.path("terminalRanges").isEmpty()).isTrue();
		assertThat(state.path("lastAnalyzedSeq").asLong()).isEqualTo(2);
		verify(gateway, times(2)).analyze(any(), any(), any(Duration.class));
	}

	@Test
	void batchPinnedUnderOlderInputRulesWithTheSameHashKeepsItsAttemptsAndBudget() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "배포 전에 세 번 실패한 가상 메시지");
		Manifest manifest = manifest("synthetic-v1");
		given(gateway.analyze(any(), any(), any(Duration.class))).willThrow(new AiCallException(true, 503));
		for (int attempt = 0; attempt < 3; attempt++) {
			given(testClock.instant()).willReturn(NOW.plusSeconds(attempt));
			assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		}
		// 이전 버전이 남긴 상태처럼 규칙 표시만 없고, 입력 해시는 현재 규칙으로 만든 것과 같다.
		jdbc.update("update chat set ai_state=ai_state #- '{riskAnalysis,inputRules}' where id=?", room.id());

		// 같은지 확인하는 중 토큰 계산이 한 번 실패해도 "입력이 다름"으로 보고 횟수를 0으로 되돌리지 않는다.
		given(gateway.countTokens(any())).willThrow(new IllegalStateException("synthetic outage")).willReturn(100);
		given(testClock.instant()).willReturn(NOW.plusSeconds(3));
		assertCode(() -> service.analyzeNext(room.id(), manifest), ErrorCode.SERVICE_UNAVAILABLE);
		assertThat(service.analyzeNext(room.id(), manifest)).isFalse();
		JsonNode state = jsonMapper.readTree(state(room)).path("riskAnalysis");
		assertThat(state.path("terminal").asBoolean()).isTrue();
		assertThat(state.path("processedThroughSeq").asLong()).isEqualTo(1);
		verify(gateway, times(3)).analyze(any(), any(), any(Duration.class));
	}

	@Test
	void batchPinnedUnderOlderInputRulesWithTheSameHashContinuesItsAttempts() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "배포 전에 한 번 실패한 가상 메시지");
		Manifest manifest = manifest("synthetic-v1");
		given(gateway.analyze(any(), any(), any(Duration.class))).willThrow(new AiCallException(true, 503));
		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		jdbc.update("update chat set ai_state=ai_state #- '{riskAnalysis,inputRules}' where id=?", room.id());

		given(testClock.instant()).willReturn(NOW.plusSeconds(1));
		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		JsonNode state = jsonMapper.readTree(state(room)).path("riskAnalysis");
		assertThat(state.path("attempts").asInt()).isEqualTo(2);
		assertThat(state.path("firstStartedAt").asString()).isEqualTo(NOW.toString());
		assertThat(state.path("inputRules").asString()).isNotBlank();
		verify(gateway, times(2)).analyze(any(), any(), any(Duration.class));
	}

	@Test
	void tokenizerOutageWhileRebuildingAPinnedBatchCancelsOnlyThisClaim() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "재시도 중 토큰 계산 장애 가상 메시지");
		Manifest manifest = manifest("synthetic-v1");
		given(gateway.analyze(any(), any(), any(Duration.class))).willThrow(new AiCallException(true, 503));
		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();

		given(gateway.countTokens(any())).willThrow(new IllegalStateException("synthetic outage")).willReturn(100);
		given(testClock.instant()).willReturn(NOW.plusSeconds(1));
		assertCode(() -> service.analyzeNext(room.id(), manifest), ErrorCode.SERVICE_UNAVAILABLE);
		JsonNode state = jsonMapper.readTree(state(room)).path("riskAnalysis");
		assertThat(state.path("terminal").asBoolean()).isFalse();
		assertThat(state.path("attempts").asInt()).isEqualTo(1);

		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		verify(gateway, times(2)).analyze(any(), any(), any(Duration.class));
	}

	@Test
	void automaticReceiptsBeyond128RemainReplayableAfterReleaseChanges() throws Exception {
		Fixture room = room();
		Manifest original = manifest("synthetic-v1");
		given(gateway.analyze(any(), any(), any(Duration.class))).willReturn(normal());
		Request first = null;
		Response firstResult = null;
		for (int sequence = 1; sequence <= 129; sequence++) {
			message(room, sequence, room.firstParticipant(), "영수증 보존 가상 메시지 " + sequence);
			assertThat(service.analyzeNext(room.id(), original)).isTrue();
			if (sequence == 1) {
				String id = jsonMapper.readTree(state(room)).path("riskAnalysis").path("attemptId").asString();
				first = new Request(UUID.fromString(id), room.id(), original);
				firstResult = service.analyze(first);
			}
		}
		assertThat(jsonMapper.readTree(state(room)).path("riskAnalysis").path("receipts").size()).isEqualTo(129);
		given(manifests.supports(original)).willReturn(false);
		message(room, 130, room.secondParticipant(), "새 릴리스 가상 메시지");
		assertThat(service.analyzeNext(room.id(), manifest("synthetic-v2"))).isTrue();
		String before = state(room);
		assertThat(service.analyze(first)).isEqualTo(firstResult);
		assertThat(state(room)).isEqualTo(before);
		Request conflict = new Request(first.attemptId(), room.id(), manifest("synthetic-v2"));
		assertCode(() -> service.analyze(conflict), ErrorCode.IDEMPOTENCY_CONFLICT);
		verify(gateway, times(130)).analyze(any(), any(), any(Duration.class));
		verify(gateway, never()).analyze(any(), any());
		consent(room.first(), false, AI_RISK_VERSION, NOW.plusSeconds(1));
		Request replay = first;
		assertCode(() -> service.analyze(replay), ErrorCode.CHAT_CLOSED);
	}

	@Test
	void insufficientTerminalRangeIsNotConvertedIntoNormalByLaterSuccess() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "문맥 부족 가상 메시지");
		Manifest manifest = manifest("synthetic-v1");
		given(gateway.analyze(any(), any(), any(Duration.class))).willReturn(new Verdict(
			Kind.INSUFFICIENT, List.of(), null, "synthetic-model-v1", "synthetic-prompt-v1"));
		for (int attempt = 0; attempt < 3; attempt++) {
			given(testClock.instant()).willReturn(NOW.plusSeconds(attempt));
			assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		}
		given(testClock.instant()).willReturn(NOW.plusSeconds(3));
		assertThat(service.analyzeNext(room.id(), manifest)).isFalse();
		JsonNode terminal = jsonMapper.readTree(state(room)).path("riskAnalysis");
		String id = terminal.path("attemptId").asString();
		assertThat(terminal.path("terminalRanges").path(id).path("kind").asString()).isEqualTo("INSUFFICIENT");
		message(room, 2, room.secondParticipant(), "나중 가상 메시지");
		given(gateway.analyze(any(), any(), any(Duration.class))).willReturn(normal());
		assertThat(service.analyzeNext(room.id(), manifest)).isTrue();
		assertThat(jsonMapper.readTree(state(room)).path("riskAnalysis").path("terminalRanges").path(id))
			.isEqualTo(terminal.path("terminalRanges").path(id));
		assertThat(notices(room)).isZero();
	}

	@Test
	void terminalRangeSurvivesConcurrentSuccessorAndLateOriginalCompletion() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "첫 가상 메시지");
		Manifest manifest = manifest("synthetic-v1");
		RiskAnalysisStore.Claim expired = store.claimNext(room.id(), manifest);
		given(testClock.instant()).willReturn(expired.deadlineAt());
		assertThat(store.claimNext(room.id(), manifest)).isNull();
		// Existing terminal rows predating the processed cursor keep their stable receipt.
		jdbc.update("update chat set ai_state=ai_state #- '{riskAnalysis,processedThroughSeq}' "
			+ "#- '{riskAnalysis,terminalRanges}' where id=?", room.id());
		message(room, 2, room.secondParticipant(), "후속 가상 메시지");
		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<RiskAnalysisStore.Claim> left = workers.submit(() -> store.claimNext(room.id(), manifest));
			Future<RiskAnalysisStore.Claim> right = workers.submit(() -> store.claimNext(room.id(), manifest));
			RiskAnalysisStore.Claim leftClaim = left.get(10, TimeUnit.SECONDS);
			RiskAnalysisStore.Claim rightClaim = right.get(10, TimeUnit.SECONDS);
			assertThat((leftClaim == null) != (rightClaim == null)).isTrue();
			RiskAnalysisStore.Claim successor = leftClaim == null ? rightClaim : leftClaim;
			assertThat(successor.input().messages().stream().filter(Message::fresh).map(Message::sequence))
				.containsExactly(2L);
			String running = state(room);
			assertThat(store.finish(expired, normal()).status()).isEqualTo("FAILED");
			assertThat(state(room)).isEqualTo(running);
			assertThat(store.finish(successor, normal()).status()).isEqualTo("SUCCEEDED");
			String completed = state(room);
			assertThat(store.finish(expired, normal()).status()).isEqualTo("FAILED");
			assertThat(state(room)).isEqualTo(completed);
			assertThat(service.analyze(expired.request()).status()).isEqualTo("FAILED");
		}
	}

	@Test
	void automaticConsentGenerationChangeCannotRestartTheBatch() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "가상 메시지");
		given(gateway.analyze(any(), any(), any(Duration.class))).willReturn(failed());
		service.analyzeNext(room.id(), manifest("synthetic-v1"));
		String before = state(room);
		consent(room.first(), true, AI_RISK_VERSION, NOW.plusSeconds(1));
		given(testClock.instant()).willReturn(NOW.plusSeconds(2));
		assertThat(service.analyzeNext(room.id(), manifest("synthetic-v1"))).isFalse();
		assertThat(state(room)).isEqualTo(before);
		verify(gateway, times(1)).analyze(any(), any(), any(Duration.class));
	}

	@Test
	void automaticInputMutationTerminatesWithoutSendingTheChangedMessages() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "가상 메시지");
		given(gateway.analyze(any(), any(), any(Duration.class))).willReturn(failed());
		service.analyzeNext(room.id(), manifest("synthetic-v1"));
		jdbc.update("update chat_message set body='변경된 가상 메시지' where chat_id=?", room.id());
		given(testClock.instant()).willReturn(NOW.plusSeconds(1));
		assertThat(service.analyzeNext(room.id(), manifest("synthetic-v1"))).isFalse();
		assertThat(jsonMapper.readTree(state(room)).path("riskAnalysis").path("terminal").asBoolean()).isTrue();
		verify(gateway, times(1)).analyze(any(), any(), any(Duration.class));
	}

	@Test
	void automaticClosedAndWithdrawnRoomsDiscardResultsAfterTheCall() throws Exception {
		for (boolean close : List.of(true, false)) {
			Fixture room = room();
			message(room, 1, room.firstParticipant(), "가상 메시지");
			given(gateway.analyze(any(), any(), any(Duration.class))).willAnswer(invocation -> {
				assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
					.isActualTransactionActive()).isFalse();
				if (close) {
					jdbc.update("update chat set closed_at=?,close_reason='LEFT' where id=?", stamp(NOW), room.id());
				} else {
					consent(room.first(), false, AI_RISK_VERSION);
				}
				return money(invocation.getArgument(1));
			});
			assertThat(service.analyzeNext(room.id(), manifest("synthetic-v1"))).isTrue();
			assertThat(notices(room)).isZero();
			assertThat(jsonMapper.readTree(state(room)).path("riskAnalysis").path("lastAnalyzedSeq").asLong())
				.isZero();
		}
	}

	@Test
	void automaticConcurrentClaimsShareOneLeaseAndCannotCreateDuplicateCalls() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "가상 메시지");
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		given(gateway.analyze(any(), any(), any(Duration.class))).willAnswer(invocation -> {
			entered.countDown();
			await(release);
			return normal();
		});
		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = workers.submit(() -> service.analyzeNext(room.id(), manifest("synthetic-v1")));
			await(entered);
			try {
				assertThat(workers.submit(() -> service.analyzeNext(room.id(), manifest("synthetic-v1")))
					.get(10, TimeUnit.SECONDS)).isFalse();
			} finally {
				release.countDown();
			}
			assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
		}
		verify(gateway, times(1)).analyze(any(), any(), any(Duration.class));
	}

	@Test
	void automaticRevalidationCannotCrossTheLeaseDeadlineOrRoomExpiry() throws Exception {
		for (int seconds : List.of(30, 300, 1)) {
			given(testClock.instant()).willReturn(NOW);
			given(gateway.countTokens(any())).willReturn(100);
			Fixture room = room();
			message(room, 1, room.firstParticipant(), "시간 경계 가상 메시지");
			RiskAnalysisStore.Claim claim = store.claimNext(room.id(), manifest("synthetic-v1"));
			if (seconds == 1) {
				jdbc.update("update chat set expires_at=? where id=?", stamp(NOW.plusSeconds(1)), room.id());
			}
			given(gateway.countTokens(any())).willAnswer(invocation -> {
				given(testClock.instant()).willReturn(NOW.plusSeconds(seconds));
				return 100;
			});
			assertThat(store.canTransmit(claim)).isFalse();
			given(testClock.instant()).willReturn(NOW);
			assertThat(store.finish(claim, normal()).status()).isEqualTo("FAILED");
			assertThat(jsonMapper.readTree(state(room)).path("riskAnalysis").path("lastAnalyzedSeq").asLong())
				.isZero();
		}
	}

	@Test
	void changedManifestDoesNotLeaveAnExpiredAutomaticReceiptRunning() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "가상 메시지");
		RiskAnalysisStore.Claim first = store.claimNext(room.id(), manifest("synthetic-v1"));
		given(testClock.instant()).willReturn(first.deadlineAt().minusSeconds(1));
		assertThat(service.analyzeNext(room.id(), manifest("synthetic-v2"))).isFalse();
		given(testClock.instant()).willReturn(first.deadlineAt());
		assertThat(service.analyzeNext(room.id(), manifest("synthetic-v2"))).isFalse();
		JsonNode state = jsonMapper.readTree(state(room)).path("riskAnalysis");
		assertThat(state.path("status").asString()).isEqualTo("FAILED");
		assertThat(state.path("terminal").asBoolean()).isTrue();
		assertThat(state.path("receipts").path(first.request().attemptId().toString())
			.path("result").path("status").asString()).isEqualTo("FAILED");
		verify(gateway, never()).analyze(any(), any(), any(Duration.class));
	}

	@Test
	void automaticServiceExecutesHttpAfterClaimCommitAndStoresTheCorrelatedResult() throws Exception {
		Fixture room = room();
		message(room, 1, room.firstParticipant(), "HTTP 가상 메시지");
		assertThat(store.candidates(room.id() - 1, 20)).contains(room.id());
		java.util.concurrent.atomic.AtomicReference<Throwable> serverFailure =
			new java.util.concurrent.atomic.AtomicReference<>();
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
			new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/internal/ai/risk-detections", exchange -> {
			try (exchange) {
				JsonNode body = jsonMapper.readTree(exchange.getRequestBody());
				assertThat(body.size()).isEqualTo(4);
				assertThat(body.path("contextId").asLong()).isEqualTo(room.id());
				assertThat(body.path("attemptId").asString())
					.isEqualTo(exchange.getRequestHeaders().getFirst("X-Request-Id"));
				assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
					.isEqualTo("Bearer synthetic-risk-token");
				assertThat(jsonMapper.readTree(state(room)).path("riskAnalysis").path("status").asString())
					.isEqualTo("RUNNING");
				byte[] response = jsonMapper.writeValueAsBytes(new HttpRiskAnalysisGateway.WireResponse(
					UUID.fromString(body.path("attemptId").asString()), manifest("synthetic-v1"), normal()));
				exchange.sendResponseHeaders(200, response.length);
				exchange.getResponseBody().write(response);
			} catch (Throwable failure) {
				serverFailure.set(failure);
			}
		});
		server.start();
		try {
			var tokens = org.mockito.Mockito.mock(com.e106.tingle.global.ai.AiServiceTokenProvider.class);
			given(tokens.issue()).willReturn("synthetic-risk-token");
			var properties = new com.e106.tingle.global.ai.AiServiceProperties("test", "synthetic", "",
				"http://127.0.0.1:" + server.getAddress().getPort());
			var client = new com.e106.tingle.global.ai.AiInternalClient(properties, tokens, jsonMapper);
			HttpRiskAnalysisGateway http = new HttpRiskAnalysisGateway(client, input -> 100);
			given(gateway.analyze(any(), any(), any(Duration.class))).willAnswer(invocation -> {
				assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
					.isActualTransactionActive()).isFalse();
				return http.analyze(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
			});
			assertThat(service.analyzeNext(room.id(), manifest("synthetic-v1"))).isTrue();
			assertThat(serverFailure.get()).isNull();
			assertThat(jsonMapper.readTree(state(room)).path("riskAnalysis").path("status").asString())
				.isEqualTo("SUCCEEDED");
			assertThat(store.candidates(room.id() - 1, 20)).doesNotContain(room.id());
		} finally {
			server.stop(0);
		}
	}

	private void assertRecordedFailureReplay(Fixture room, Request request, Response response, Instant finishedAt)
		throws Exception {
		assertThat(response.status()).isEqualTo("FAILED");
		assertThat(service.analyze(request)).isEqualTo(response);
		assertThat(store.claim(request).cached()).isEqualTo(response);
		JsonNode recorded = jsonMapper.readTree(state(room)).path("riskAnalysis");
		assertThat(recorded.path("status").asString()).isEqualTo("FAILED");
		assertThat(recorded.path("kind").asString()).isEqualTo("FAILED");
		assertThat(recorded.path("retryAt").asString()).isEqualTo(finishedAt.plusSeconds(1).toString());
		assertThat(recorded.path("lastAnalyzedSeq").asLong()).isZero();
		assertThat(notices(room)).isZero();
		verify(gateway, times(1)).analyze(any(), any());
	}

	private Fixture room() throws Exception {
		long first = onboard();
		long second = onboard();
		Instant created = NOW.minusSeconds(60);
		long matching = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,status,proposed_at) "
			+ "values (?,?,'ACCEPTED',?) returning id", Long.class, Math.min(first, second), Math.max(first, second),
			stamp(created));
		for (long userId : List.of(first, second)) {
			jdbc.update("insert into matching_participant(matching_id,user_id,response,responded_at) "
				+ "values (?,?,'ACCEPTED',?)", matching, userId, stamp(created));
		}
		long roomId = jdbc.queryForObject("insert into chat(matching_id,created_at,expires_at,last_message_seq,"
			+ "icebreakers,ai_state) values (?,?,?,0,'{}','{}') returning id", Long.class, matching,
			stamp(created), stamp(created.plus(Duration.ofDays(7))));
		long firstParticipant = participant(roomId, first);
		long secondParticipant = participant(roomId, second);
		return new Fixture(roomId, first, second, firstParticipant, secondParticipant);
	}

	private long onboard() throws Exception {
		JsonNode tokens = login("google", newSocialId());
		// Existing synthetic social/analysis/ACK fixtures establish eligibility, not real external verification.
		completeOnboarding(tokens);
		return userIdOf(tokens);
	}

	private long participant(long roomId, long userId) {
		return jdbc.queryForObject("insert into chat_participant(chat_id,user_id) values (?,?) returning id",
			Long.class, roomId, userId);
	}

	private void message(Fixture room, long sequence, long participant, String text) {
		jdbc.update("insert into chat_message(chat_id,sender_participant_id,client_message_id,request_key,"
			+ "request_hash,seq,body,created_at) values (?,?,?,?,?,?,?,?)", room.id(), participant,
			UUID.randomUUID(), UUID.randomUUID(), new byte[32], sequence, text, stamp(NOW.minusSeconds(1)));
		jdbc.update("update chat set last_message_seq=greatest(last_message_seq,?) where id=?", sequence, room.id());
	}

	private void consent(long userId, boolean agreed, String version) {
		consent(userId, agreed, version, NOW);
	}

	private void consent(long userId, boolean agreed, String version, Instant recordedAt) {
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (?,'AI_RISK_DETECTION',?,?,?)", userId, version, agreed, stamp(recordedAt));
	}

	private String state(Fixture room) {
		return jdbc.queryForObject("select ai_state::text from chat where id=?", String.class, room.id());
	}

	private long notices(Fixture room) {
		return jdbc.queryForObject("select count(*) from chat_safety_notice n join chat_participant p "
			+ "on p.id=n.chat_participant_id where p.chat_id=?", Long.class, room.id());
	}

	private static Request request(Fixture room) {
		return new Request(UUID.randomUUID(), room.id(), manifest("synthetic-v1"));
	}

	private static Manifest manifest(String version) {
		return new Manifest(UUID.fromString("11111111-1111-4111-8111-111111111111"), version, version, version,
			version, version, version, version, version, version, "0".repeat(64));
	}

	private static Verdict normal() {
		return new Verdict(Kind.NORMAL, List.of(), 0.9, "synthetic-model-v1", "synthetic-prompt-v1");
	}

	private static Verdict failed() {
		return new Verdict(Kind.FAILED, List.of(), null, "synthetic-model-v1", "synthetic-prompt-v1");
	}

	/** 운영 SQL 과 같은 두 문장. Hibernate 가 만든 스키마는 제약, schema.sql 로 만든 스키마는 인덱스다. */
	private void dropNoticeUniqueness() {
		jdbc.execute("alter table chat_safety_notice drop constraint if exists uq_chat_safety_notice_once");
		jdbc.execute("drop index if exists uq_chat_safety_notice_once");
	}

	private static Verdict harassment(Input input) {
		long sequence = input.messages().stream().filter(Message::fresh).mapToLong(Message::sequence).max()
			.orElseThrow();
		return new Verdict(Kind.RISK, List.of(new Finding(sequence, Category.HARASSMENT, NoticeType.HARASSMENT,
			Audience.BOTH)), 0.8, "synthetic-model-v1", "synthetic-prompt-v1");
	}

	private static Verdict money(Input input) {
		long sequence = input.messages().stream().filter(Message::fresh).mapToLong(Message::sequence).max()
			.orElseThrow();
		return new Verdict(Kind.RISK, List.of(new Finding(sequence, Category.FRAUD, NoticeType.MONEY_REQUEST,
			Audience.RECIPIENT)), 0.8, "synthetic-model-v1", "synthetic-prompt-v1");
	}

	private static void assertCode(Runnable action, ErrorCode code) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
	}

	private static Timestamp stamp(Instant instant) {
		return Timestamp.from(instant);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(15, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Synthetic model was not released");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Synthetic model interrupted", exception);
		}
	}

	private record Fixture(long id, long first, long second, long firstParticipant, long secondParticipant) {
	}
}
