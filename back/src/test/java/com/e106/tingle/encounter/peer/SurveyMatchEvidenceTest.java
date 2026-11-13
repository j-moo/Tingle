package com.e106.tingle.encounter.peer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.SourceEvidence;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SurveyMatchEvidenceTest {
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void sameAnswersBecomeConcreteSharedSentences() {
		Map<String, JsonNode> left = answers("Q034", "3", "Q069", "3", "Q036", "2", "Q071", "2", "Q038", "4", "Q073",
			"4", "Q052", "[\"evening\",\"night\"]", "Q087", "\"any\"", "Q053", "\"nearby\"", "Q088", "[\"nearby\"]");
		Map<String, JsonNode> right = answers("Q034", "3", "Q069", "3", "Q036", "2", "Q071", "2", "Q038", "4", "Q073",
			"4", "Q052", "[\"night\",\"evening\",\"irregular\"]", "Q087", "\"any\"", "Q053", "\"nearby\"",
			"Q088", "[\"nearby\"]");
		List<SourceEvidence> evidence = SurveyMatchEvidence.build(left, right).evidence();
		assertThat(evidence).extracting(SourceEvidence::fact).containsExactly(
			"두 분 다 하루에 몇 번씩 연락하는 게 좋대요.", "두 분 다 일주일에 한 번쯤 만나고 싶대요.",
			"두 분 다 데이트는 대부분 미리 정해 두는 편이래요.", "두 분 다 저녁이나 늦은 밤에 만나는 게 편하대요.",
			"두 분 다 하루 데이트는 근교 정도까지 이동하는 게 좋대요.");
		assertThat(evidence).extracting(SourceEvidence::id).containsExactly("e1", "e2", "e3", "e4", "e5");
		assertThat(evidence).allMatch(item -> "SHARED_REPORTED_BEHAVIOR".equals(item.kind())
			&& "BOTH".equals(item.direction()));
		assertThat(evidence.getFirst().sourceRef()).isEqualTo("survey:SIS-Q-1.1-100:Q034");
		assertThat(evidence).extracting(SourceEvidence::topic)
			.containsExactly("contact", "planning", "planning", "planning", "activity");
	}

	@Test
	void comparesEachAnswerWithThePartnersWish() {
		// 연락: A=2, B=3, 서로 바라는 값이 상대 답과 1 이내 → 서로 맞음
		// 만남: A=1(B는 4를 바람, 안 맞음), B=4(A는 any) → B만 A의 바람에 맞음
		// 계획: A=1, B=5, 서로 안 맞음 → 다름 / 시간대: 겹침 없음, 서로 안 맞음 → 다름
		// 이동: A=local, B=stay, 둘 다 상대 답을 원하지 않음 → 다름
		Map<String, JsonNode> left = answers("Q034", "2", "Q069", "3", "Q036", "1", "Q071", "\"any\"", "Q038", "1",
			"Q073", "1", "Q052", "[\"morning\"]", "Q087", "[\"morning\"]", "Q053", "\"local\"", "Q088", "[\"local\"]");
		Map<String, JsonNode> right = answers("Q034", "3", "Q069", "2", "Q036", "4", "Q071", "4", "Q038", "5",
			"Q073", "5", "Q052", "[\"night\"]", "Q087", "[\"night\"]", "Q053", "\"stay\"", "Q088", "[\"stay\"]");
		List<SourceEvidence> evidence = SurveyMatchEvidence.build(left, right).evidence();
		assertThat(evidence).extracting(SourceEvidence::kind).containsExactly("MUTUAL_PREFERENCE_FIT",
			"ONE_WAY_PREFERENCE_FIT", "DIFFERENCE", "DIFFERENCE", "DIFFERENCE");
		assertThat(evidence.get(0).fact()).isEqualTo("연락 빈도는 두 분이 서로 바라는 정도가 잘 맞아요.");
		assertThat(evidence.get(1).direction()).isEqualTo("B_TO_A");
		assertThat(evidence.get(1).fact()).isEqualTo("A가 바라는 만남 빈도에 B가 잘 맞아요.");
		assertThat(evidence.get(4).fact()).isEqualTo("하루 데이트 이동 범위는 두 분 생각이 달라요.");
	}

	@Test
	void skipsUnknownMissingAndOneStepMismatchWithoutCreatingNumbersForThem() {
		Map<String, JsonNode> left = answers("Q034", "\"unknown\"", "Q069", "3", "Q036", "2", "Q071", "5",
			"Q052", "[\"morning\"]", "Q087", "\"any\"");
		Map<String, JsonNode> right = answers("Q034", "3", "Q069", "3", "Q036", "3", "Q071", "5",
			"Q052", "[\"morning\"]", "Q087", "\"any\"", "Q038", "2", "Q073", "2");
		List<SourceEvidence> evidence = SurveyMatchEvidence.build(left, right).evidence();
		// 연락은 unknown, 만남은 한 단계 차이인데 서로 바람과 안 맞음, 계획·이동은 A의 답이 없음 → 시간대만 남는다.
		assertThat(evidence).extracting(SourceEvidence::id).containsExactly("e4");
		assertThat(evidence.getFirst().fact()).isEqualTo("두 분 다 오전에 만나는 게 편하대요.");
	}

	@Test
	void displayOrderFollowsEachViewersQ100WithMutualFirst() {
		Map<String, JsonNode> left = answers("Q034", "2", "Q069", "3", "Q036", "1", "Q071", "\"any\"", "Q038", "1",
			"Q073", "1", "Q052", "[\"morning\"]", "Q087", "\"any\"", "Q100", "\"adjust\"");
		Map<String, JsonNode> right = answers("Q034", "3", "Q069", "2", "Q036", "4", "Q071", "4", "Q038", "5",
			"Q073", "5", "Q052", "[\"morning\"]", "Q087", "\"any\"", "Q100", "\"my_wishes\"");
		Map<String, List<String>> display = SurveyMatchEvidence.build(left, right).displayByViewer();
		// e1 서로 맞음, e2 B가 A의 바람에 맞음, e3 다름, e4 같은 행동
		assertThat(display.get("A")).containsExactly("e1", "e3", "e4", "e2");
		assertThat(display.get("B")).containsExactly("e1", "e4", "e2", "e3");
	}

	@Test
	void joinsTimeSlotsAndTopicParticlesNaturally() {
		Map<String, JsonNode> left = answers("Q052", "[\"morning\",\"afternoon\",\"evening\"]", "Q087",
			"[\"night\"]", "Q038", "1", "Q073", "1");
		Map<String, JsonNode> right = answers("Q052", "[\"evening\",\"afternoon\",\"morning\"]", "Q087",
			"[\"night\"]", "Q038", "5", "Q073", "5");
		List<SourceEvidence> evidence = SurveyMatchEvidence.build(left, right).evidence();
		assertThat(evidence).extracting(SourceEvidence::fact).containsExactly(
			"데이트 계획 방식은 두 분 생각이 달라요.", "두 분 다 오전, 오후나 저녁에 만나는 게 편하대요.");
	}

	@Test
	void valuesOutsideTheSurveyOptionsNeverBecomeEvidence() {
		// 설문 보기에 없는 값·너무 큰 숫자·중첩 배열은 그 문항만 빠지고 예외로 판정을 막지 않는다.
		Map<String, JsonNode> left = answers("Q034", "4294967297", "Q069", "1", "Q036", "[[2]]", "Q071", "2",
			"Q052", "[\"bogus\"]", "Q087", "[\"bogus\"]", "Q053", "\"xyz\"", "Q088", "[\"far\"]");
		Map<String, JsonNode> right = answers("Q034", "1", "Q069", "1", "Q036", "2", "Q071", "2",
			"Q052", "[\"bogus\"]", "Q087", "[\"bogus\"]", "Q053", "\"xyz\"", "Q088", "[\"far\"]");
		assertThat(SurveyMatchEvidence.build(left, right).evidence()).isEmpty();
	}

	@Test
	void noSurveyMeansNoEvidence() {
		SurveyMatchEvidence.Result result = SurveyMatchEvidence.build(Map.of(), Map.of());
		assertThat(result.evidence()).isEmpty();
		assertThat(result.displayByViewer()).containsEntry("A", List.of()).containsEntry("B", List.of());
	}

	private Map<String, JsonNode> answers(String... pairs) {
		Map<String, JsonNode> values = new HashMap<>();
		for (int index = 0; index < pairs.length; index += 2) {
			values.put(pairs[index], mapper.readTree(pairs[index + 1]));
		}
		return values;
	}
}
