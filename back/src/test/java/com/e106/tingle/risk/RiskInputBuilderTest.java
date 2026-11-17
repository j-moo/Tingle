package com.e106.tingle.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.risk.RiskAnalysisTypes.Input;
import com.e106.tingle.risk.RiskAnalysisTypes.Manifest;
import com.e106.tingle.risk.RiskAnalysisTypes.Message;
import com.e106.tingle.risk.RiskAnalysisTypes.Verdict;

import tools.jackson.databind.json.JsonMapper;

class RiskInputBuilderTest {

	private static final Manifest MANIFEST = new Manifest(UUID.randomUUID(), "survey", "wording", "scoring", "types",
		"analysis", "reference", "matching", "policy", "engine", "0".repeat(64));

	@Test
	void masksTheSelectedCopyWithoutChangingStoredMessagesOrUsingAnyNetwork() {
		String text = "여기로 https://example.com/private?q=secret, hello@example.com 또는 010-0000-0000";
		Message original = message(1, text, true);
		Input input = builder(value -> 1).build(List.of(original), MANIFEST);
		assertThat(input.messages().getFirst().text()).contains("[웹 주소 차단]", "[이메일 숨김]", "[전화번호 숨김]")
			.doesNotContain("example.com", "private", "secret", "010-0000-0000");
		assertThat(input.messages().getFirst().urlMasked()).isTrue();
		assertThat(original.text()).isEqualTo(text);
		assertThat(input.manifest()).isEqualTo(MANIFEST);
	}

	@Test
	void masksBareUrlPrefixesThatTheAiServerRejects() {
		// AI 서버 http_api.py의 _RAW_URL과 같은 기준이다.
		Pattern aiRawUrl = Pattern.compile("(?i)https?://|www\\.");
		for (String text : List.of("www. 지금 돈 보내", "https:// 링크", "끝에 www.", "HTTP://", "awww.x")) {
			Message masked = builder(value -> 1).build(List.of(message(1, text, true)), MANIFEST).messages().getFirst();
			assertThat(aiRawUrl.matcher(masked.text()).find()).as(text).isFalse();
			assertThat(masked.urlMasked()).as(text).isTrue();
		}
	}

	@Test
	void anApostropheInsideTheUrlQueryIsMaskedWithIt() {
		for (String text : List.of("https://example.com/r?k=synthetic'opaque-demo",
			"https://example.com/r?k=synthetic''opaque-demo")) {
			Message masked = builder(value -> 1).build(List.of(message(1, text, true)), MANIFEST).messages().getFirst();
			assertThat(masked.text()).as(text).isEqualTo("[웹 주소 차단]");
		}
	}

	@Test
	void unicodeSpacesAndQuotesEndTheMaskedUrlSoTheThreatAfterItIsKept() {
		for (String text : List.of("www.　너를 죽일 거야", "www. 너를 죽일 거야", "https://x.y 너를 죽일 거야",
			"https://x.y/" + " " + "너를 죽일 거야", "www.\"너를 죽일 거야\"",
			"https://“너를 죽일 거야”", "https://x.y/'너를 죽일 거야'")) {
			Message masked = builder(value -> 1).build(List.of(message(1, text, true)), MANIFEST).messages().getFirst();
			assertThat(masked.text()).as(text).contains("너를 죽일 거야").doesNotContain("www.", "https://");
		}
	}

	@Test
	void storedBodyBreakingTheInputRulesIsSkippedAloneAndItsNeighboursAreKept() {
		// 1 정상 → 2 탭만 → 3 정상: 첫 회차는 1만, 2가 첫 신규가 되면 2만 건너뛰기, 문맥으로 오면 2만 빠진다.
		List<Message> first = List.of(message(1, "위협 1", true), message(2, "\t", true), message(3, "위협 3", true));
		assertThat(builder(value -> 1).build(first, MANIFEST).messages()).extracting(Message::sequence)
			.containsExactly(1L);
		assertThatThrownBy(() -> builder(value -> 1).build(List.of(message(1, "위협 1", false),
			message(2, "\t", true), message(3, "위협 3", true)), MANIFEST))
			.isInstanceOfSatisfying(RiskInputBuilder.UnanalyzableMessage.class,
				skipped -> assertThat(skipped.sequence()).isEqualTo(2));
		Input next = builder(value -> 1).build(List.of(message(1, "위협 1", false), message(2, "\t", false),
			message(3, "위협 3", true)), MANIFEST);
		assertThat(next.messages()).extracting(Message::sequence).containsExactly(1L, 3L);
	}

	@Test
	void includesTheLargestConsecutiveFreshPrefixAndNeverSkipsAnOversizedFreshMessage() {
		List<Message> messages = List.of(message(1, "old context", false), message(2, "first", true),
			message(3, "x".repeat(4001), true), message(4, "later", true));
		Input input = builder(value -> 1).build(messages, MANIFEST);
		assertThat(input.messages().stream().filter(Message::fresh).map(Message::sequence)).containsExactly(2L);
		assertThat(input.messages()).extracting(Message::sequence).doesNotContain(3L, 4L);
	}

	@Test
	void anOversizedContextMessageIsDroppedAloneWithoutErasingEarlierContext() {
		// 9번은 앞서 건너뛴 한도 초과 메시지. 줄이기가 오래된 것부터 지우면 1~8번까지 다 사라지던 문제.
		List<Message> messages = new ArrayList<>();
		IntStream.rangeClosed(1, 8).forEach(i -> messages.add(message(i, "context " + i, false)));
		messages.add(message(9, "x".repeat(4001), false));
		messages.add(message(10, "fresh", true));
		Input input = builder(value -> 1).build(messages, MANIFEST);
		assertThat(input.messages()).extracting(Message::sequence)
			.containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 10L);
	}

	@Test
	void contextMessageOverTheTokenLimitAloneIsAlsoDroppedAlone() {
		// 글자 수는 한도 안이지만 토큰만 3,000을 넘어 건너뛴 메시지도 같은 방식으로 혼자만 빠져야 한다.
		List<Message> messages = new ArrayList<>();
		IntStream.rangeClosed(1, 8).forEach(i -> messages.add(message(i, "context " + i, false)));
		messages.add(message(9, "heavy", false));
		messages.add(message(10, "fresh", true));
		ToIntFunction<Input> tokens = value -> value.messages().stream()
			.anyMatch(message -> "heavy".equals(message.text())) ? 3001 : value.messages().size();
		Input input = builder(tokens).build(messages, MANIFEST);
		assertThat(input.messages()).extracting(Message::sequence)
			.containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 10L);
	}

	@Test
	void discardsOldContextBeforeAnyFreshMessageWhenTheMessageCapIsReached() {
		List<Message> messages = new ArrayList<>();
		IntStream.rangeClosed(1, 12).forEach(i -> messages.add(message(i, "old " + i, false)));
		IntStream.rangeClosed(13, 24).forEach(i -> messages.add(message(i, "new " + i, true)));
		Input input = builder(value -> 1).build(messages, MANIFEST);
		assertThat(input.messages()).hasSize(10).allMatch(Message::fresh);
		assertThat(input.messages()).extracting(Message::sequence)
			.containsExactly(13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L, 21L, 22L);
	}

	@Test
	void keepsRecentContextInOrderAndReturnsAnImmutableInput() {
		List<Message> messages = new ArrayList<>(List.of(message(1, "a", false), message(2, "b", false),
			message(3, "c", false), message(4, "new", true)));
		Input input = builder(value -> value.messages().size() * 1000).build(messages, MANIFEST);
		messages.clear();
		assertThat(input.messages()).extracting(Message::sequence).containsExactly(2L, 3L, 4L);
		assertThatThrownBy(() -> input.messages().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void countsUnicodeCodePointsAndDoesNotTreatCharactersAsTokens() {
		List<Message> messages = IntStream.rangeClosed(1, 5)
			.mapToObj(i -> message(i, "😀".repeat(1000), true)).toList();
		assertThat(builder(value -> 1).build(messages, MANIFEST).messages()).hasSize(4);
		assertThat(builder(value -> value.messages().size() * 1500).build(messages, MANIFEST).messages()).hasSize(2);
	}

	@Test
	void refusesTheFirstUnprocessableFreshMessageInsteadOfAdvancingPastIt() {
		assertThatThrownBy(() -> builder(value -> 1).build(List.of(message(1, "a".repeat(4001), true)), MANIFEST))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
		assertThatThrownBy(() -> builder(value -> 3001).build(List.of(message(1, "short", true)), MANIFEST))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
	}

	@Test
	void rejectsBrokenTokenizerCountsWithoutInventingACharacterBasedFallback() {
		assertThatThrownBy(() -> builder(value -> -1).build(List.of(message(1, "short", true)), MANIFEST))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
		assertThatThrownBy(() -> builder(value -> {
			throw new IllegalStateException("synthetic tokenizer failure");
		}).build(List.of(message(1, "short", true)), MANIFEST))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
	}

	@Test
	void rejectsNonChronologicalDuplicateOrNonPseudonymousMessages() {
		List<List<Message>> invalid = List.of(List.of(message(2, "later", true), message(1, "earlier", true)),
			List.of(message(1, "one", true), message(1, "duplicate", true)),
			List.of(message(1, "new", true), message(2, "old", false)),
			List.of(new Message(1, "12345", "private user id", true, false)),
			List.of(message(0, "invalid sequence", true)), List.of(message(1, "context only", false)));
		for (List<Message> messages : invalid) {
			assertThatThrownBy(() -> builder(value -> 1).build(messages, MANIFEST))
				.isInstanceOf(BusinessException.class);
		}
	}

	@Test
	void rejectsMalformedUnicodeAndControlsBeforeAnyTokenizerReceivesThem() {
		for (String text : List.of("\u00a0\u3000", "text\u0000", "text\ud800")) {
			assertThatThrownBy(() -> builder(value -> {
				throw new AssertionError("Tokenizer must not see invalid input");
			}).build(List.of(message(1, text, true)), MANIFEST)).isInstanceOf(BusinessException.class);
		}
	}

	@Test
	void catalogWithoutConfiguredReleaseHasNoApprovedManifest() {
		assertThat(new RiskManifestCatalog(new RiskReleaseProperties(""),
			JsonMapper.builder().build()).supports(MANIFEST)).isFalse();
	}

	private RiskInputBuilder builder(ToIntFunction<Input> tokens) {
		return new RiskInputBuilder(new RiskAnalysisGateway() {
			@Override
			public void requireAvailable() {
			}

			@Override
			public int countTokens(Input input) {
				return tokens.applyAsInt(input);
			}

			@Override
			public Verdict analyze(Input input) {
				throw new AssertionError("Input preparation must not invoke a model or network");
			}
		});
	}

	private static Message message(long sequence, String text, boolean fresh) {
		return new Message(sequence, sequence % 2 == 0 ? "B" : "A", text, fresh, false);
	}
}
