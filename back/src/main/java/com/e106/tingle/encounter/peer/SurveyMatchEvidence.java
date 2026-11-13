package com.e106.tingle.encounter.peer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.SourceEvidence;

import tools.jackson.databind.JsonNode;

/**
 * 키링 매칭의 매칭 이유 근거. 두 사람의 설문(SIS-Q-1.1-100) 중 데이트 생활 5쌍만 비교해 AI와 맞춘 고정 문장을 만든다.
 * 설문 원응답은 밖으로 내보내지 않는다. 성격·애착·술·담배·종교·자녀 등과 Q040(아이스브레이커 주제 동의 필요)은 쓰지 않는다.
 * 매칭 계산은 키링이 설문 전체로 하고, 여기서는 이미 성립한 매칭을 설명할 재료만 만든다.
 */
final class SurveyMatchEvidence {
	static final String SURVEY_VERSION = "SIS-Q-1.1-100";
	private static final List<String> SLOTS = List.of("morning", "afternoon", "evening", "night");
	private static final Set<String> SLOT_OPTIONS = Set.of("morning", "afternoon", "evening", "night", "irregular");
	private static final Map<String, String> SLOT_LABELS = Map.of("morning", "오전", "afternoon", "오후",
		"evening", "저녁", "night", "늦은 밤");
	// 문장은 해요체·전해 듣는 말투("~대요")로, 본인이 답한 것 이상을 말하지 않는다(AI와 2026-11-12 합의).
	private static final List<Pair> PAIRS = List.of(
		new Pair("Q034", "Q069", "contact", "연락 빈도", Kind.SCALE, Map.of(
			"1", "매일 연락하지 않아도 괜찮대요", "2", "하루 한 번 정도 연락하는 게 좋대요",
			"3", "하루에 몇 번씩 연락하는 게 좋대요", "4", "틈날 때마다 연락하는 게 좋대요",
			"5", "거의 실시간으로 연락하는 게 좋대요")),
		new Pair("Q036", "Q071", "planning", "만남 빈도", Kind.SCALE, Map.of(
			"1", "격주 정도로 만나도 괜찮대요", "2", "일주일에 한 번쯤 만나고 싶대요",
			"3", "일주일에 두 번쯤 만나고 싶대요", "4", "일주일에 세 번쯤 만나고 싶대요",
			"5", "일주일에 네 번 이상 만나고 싶대요")),
		new Pair("Q038", "Q073", "planning", "데이트 계획 방식", Kind.SCALE, Map.of(
			"1", "데이트는 당일에 정하는 편이래요", "2", "큰 방향만 정해 두고 당일에 고르는 편이래요",
			"3", "데이트 계획은 그때그때 다르대요", "4", "데이트는 대부분 미리 정해 두는 편이래요",
			"5", "시간과 장소까지 미리 꼼꼼히 정하는 편이래요")),
		new Pair("Q052", "Q087", "planning", "데이트 시간대", Kind.SLOTS, Map.of()),
		new Pair("Q053", "Q088", "activity", "하루 데이트 이동 범위", Kind.CHOICE, Map.of(
			// 이동 범위 자체만 말한다(어떤 활동을 좋아한다고 늘려 말하지 않음).
			"local", "하루 데이트는 동네 안에서 다니는 게 좋대요", "stay", "하루 데이트는 한곳에 오래 머무는 게 좋대요",
			"district", "하루 데이트는 한 지역 안에서 여러 곳을 다니는 게 좋대요",
			"nearby", "하루 데이트는 근교 정도까지 이동하는 게 좋대요",
			"far", "하루 데이트는 먼 곳까지 이동하는 게 좋대요")));
	// Q100(설명 순서) 답 → 먼저 보여 줄 근거 묶음. 서로 맞음은 항상 맨 앞이다.
	private static final List<String> DEFAULT_ORDER = List.of("common", "my_wishes", "their_wishes", "adjust");

	private SurveyMatchEvidence() {
	}

	/** left(A)·right(B)는 문항 ID → 답. 답이 없거나 형식이 다르면 그 문항은 근거를 만들지 않는다. */
	static Result build(Map<String, JsonNode> left, Map<String, JsonNode> right) {
		List<SourceEvidence> evidence = new ArrayList<>();
		for (int index = 0; index < PAIRS.size(); index++) {
			SourceEvidence item = compare(PAIRS.get(index), "e" + (index + 1), left, right);
			if (item != null) {
				evidence.add(item);
			}
		}
		return new Result(List.copyOf(evidence), Map.of("A", order(evidence, "A", left.get("Q100")),
			"B", order(evidence, "B", right.get("Q100"))));
	}

	private static SourceEvidence compare(Pair pair, String id, Map<String, JsonNode> left,
		Map<String, JsonNode> right) {
		JsonNode selfA = left.get(pair.self());
		JsonNode selfB = right.get(pair.self());
		JsonNode wishA = left.get(pair.wish());
		JsonNode wishB = right.get(pair.wish());
		if (!pair.validSelf(selfA) || !pair.validSelf(selfB) || !pair.validWish(wishA) || !pair.validWish(wishB)) {
			return null;
		}
		String sourceRef = "survey:" + SURVEY_VERSION + ":" + pair.self();
		String shared = shared(pair, selfA, selfB);
		if (shared != null) {
			return new SourceEvidence(id, "BOTH", "SHARED_REPORTED_BEHAVIOR", pair.topic(),
				"두 분 다 " + shared + ".", sourceRef);
		}
		boolean aFitsB = fits(pair.kind(), selfA, wishB);
		boolean bFitsA = fits(pair.kind(), selfB, wishA);
		if (aFitsB && bFitsA) {
			return new SourceEvidence(id, "BOTH", "MUTUAL_PREFERENCE_FIT", pair.topic(),
				topicOf(pair.label()) + " 두 분이 서로 바라는 정도가 잘 맞아요.", sourceRef);
		}
		if (aFitsB || bFitsA) {
			String fitter = aFitsB ? "A" : "B";
			String wisher = aFitsB ? "B" : "A";
			return new SourceEvidence(id, fitter + "_TO_" + wisher, "ONE_WAY_PREFERENCE_FIT", pair.topic(),
				wisher + "가 바라는 " + pair.label() + "에 " + fitter + "가 잘 맞아요.", sourceRef);
		}
		if (pair.kind() != Kind.SCALE || Math.abs(selfA.intValue() - selfB.intValue()) >= 2) {
			return new SourceEvidence(id, "BOTH", "DIFFERENCE", pair.topic(),
				topicOf(pair.label()) + " 두 분 생각이 달라요.", sourceRef);
		}
		// 한 단계 차이인데 서로 바라는 것과도 맞지 않으면 같다고도 다르다고도 하지 않는다.
		return null;
	}

	/** 두 사람의 답이 같으면 그 행동 문구, 아니면 null. */
	private static String shared(Pair pair, JsonNode selfA, JsonNode selfB) {
		return switch (pair.kind()) {
			case SCALE -> selfA.intValue() == selfB.intValue() ? pair.phrases().get(selfA.asString()) : null;
			case CHOICE -> selfA.asString().equals(selfB.asString()) ? pair.phrases().get(selfA.asString()) : null;
			case SLOTS -> {
				Set<String> common = strings(selfA);
				common.retainAll(strings(selfB));
				List<String> slots = SLOTS.stream().filter(common::contains).map(SLOT_LABELS::get).toList();
				if (!slots.isEmpty()) {
					yield or(slots) + "에 만나는 게 편하대요";
				}
				yield common.contains("irregular") ? "일정이 불규칙한 편이래요" : null;
			}
		};
	}

	/** self가 상대의 바람(wish)에 맞는지. 상관없음(any)은 항상 맞음이다. */
	private static boolean fits(Kind kind, JsonNode self, JsonNode wish) {
		if (wish.isString() && "any".equals(wish.asString())) {
			return true;
		}
		return switch (kind) {
			case SCALE -> Math.abs(self.intValue() - wish.intValue()) <= 1;
			case CHOICE -> strings(wish).contains(self.asString());
			case SLOTS -> {
				Set<String> overlap = strings(self);
				overlap.retainAll(strings(wish));
				yield !overlap.isEmpty();
			}
		};
	}

	/** 열람자의 Q100 답을 앞세워 근거 순서를 정한다. A_TO_B는 A가 B의 바람에 맞는다는 뜻이다. */
	private static List<String> order(List<SourceEvidence> evidence, String viewer, JsonNode q100) {
		List<String> groups = new ArrayList<>();
		if (q100 != null && q100.isString() && DEFAULT_ORDER.contains(q100.asString())) {
			groups.add(q100.asString());
		}
		groups.addAll(DEFAULT_ORDER);
		List<String> preferred = List.copyOf(new LinkedHashSet<>(groups));
		String partner = "A".equals(viewer) ? "B" : "A";
		return evidence.stream().sorted(Comparator.comparingInt((SourceEvidence item) -> {
			String group = switch (item.kind()) {
				case "MUTUAL_PREFERENCE_FIT" -> null;
				case "SHARED_REPORTED_BEHAVIOR" -> "common";
				case "DIFFERENCE" -> "adjust";
				default -> item.direction().equals(partner + "_TO_" + viewer) ? "my_wishes" : "their_wishes";
			};
			return group == null ? -1 : preferred.indexOf(group);
		})).map(SourceEvidence::id).toList();
	}

	/** "오전, 오후나 저녁"처럼 마지막 둘을 "(이)나"로 잇는다. */
	private static String or(List<String> words) {
		if (words.size() == 1) {
			return words.getFirst();
		}
		String before = words.get(words.size() - 2);
		return String.join(", ", words.subList(0, words.size() - 1))
			+ (hasFinalConsonant(before) ? "이나 " : "나 ") + words.getLast();
	}

	/** "연락 빈도는", "데이트 계획 방식은"처럼 받침에 맞춰 은/는을 붙인다. */
	private static String topicOf(String word) {
		return word + (hasFinalConsonant(word) ? "은" : "는");
	}

	private static boolean hasFinalConsonant(String word) {
		char last = word.charAt(word.length() - 1);
		return last >= '가' && last <= '힣' && (last - '가') % 28 != 0;
	}

	private static Set<String> strings(JsonNode node) {
		Set<String> values = new LinkedHashSet<>();
		node.valueStream().forEach(item -> values.add(item.asString()));
		return values;
	}

	record Result(List<SourceEvidence> evidence, Map<String, List<String>> displayByViewer) {
	}

	private record Pair(String self, String wish, String topic, String label, Kind kind,
		Map<String, String> phrases) {

		/** 설문 파일(SIS-Q-1.1-100)에 정해진 보기 값만 받는다. 저장값이 깨졌으면 그 문항은 근거에서 빠진다. */
		boolean validSelf(JsonNode value) {
			return value != null && switch (kind) {
				case SCALE -> value.isInt() && value.intValue() >= 1 && value.intValue() <= 5;
				case CHOICE -> value.isString() && phrases.containsKey(value.asString());
				case SLOTS -> options(value, SLOT_OPTIONS);
			};
		}

		/** 상대에게 바라는 답. 상관없음(any)도 받는다. */
		boolean validWish(JsonNode value) {
			if (value != null && value.isString() && "any".equals(value.asString())) {
				return true;
			}
			return switch (kind) {
				case SCALE -> validSelf(value);
				case CHOICE -> options(value, phrases.keySet());
				case SLOTS -> options(value, SLOT_OPTIONS);
			};
		}

		private static boolean options(JsonNode value, Set<String> allowed) {
			return value != null && value.isArray() && !value.isEmpty()
				&& value.valueStream().allMatch(item -> item.isString() && allowed.contains(item.asString()));
		}
	}

	private enum Kind {
		SCALE, CHOICE, SLOTS
	}
}
