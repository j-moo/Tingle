package com.e106.tingle.rule;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.e106.tingle.encounter.proof.Ter2ProofVerifier;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.survey.SurveyAnswer;
import com.e106.tingle.survey.SurveyAnswerValidator;
import com.e106.tingle.survey.SurveyCatalog;
import com.e106.tingle.survey.SurveyCatalog.SurveyDefinition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 규칙 정의 형식 등록부. (종류, schemaVersion)마다 허용하는 칸과 값을 코드로 정해 두고, 그 밖의 칸은 모두 거절한다.
 * 정의는 데이터일 뿐 서버가 실행하지 않는다. 응답 규칙(responseSchema)도 SurveyAnswerValidator 가 아는 규칙만 허용한다
 * (임의 코드·SQL·파일·주소를 넣을 자리가 없다).
 * 지금 서버가 실제로 쓰는 형식은 설문(QUESTIONNAIRE)과 설문 이관 규칙(MIGRATION) 둘이다. 문항 수·척도·보기 수는 정의 파일에서 읽는다.
 * 매칭 규칙(MATCHING)·제품 정책(PRODUCT_POLICY)은 키링 펌웨어(iot/src/matching.c)가 실제로 쓰는 값을 기록한다.
 * 매칭 판정은 키링이 하고 서버는 이 값으로 다시 계산하지 않는다. 어느 규칙 버전이었는지 추적하는 근거다.
 */
final class RuleDocuments {

	// 이 서버가 검사·실행할 수 있는 규칙 엔진 버전. 조합의 engineVersion 이 이것과 같아야 검증을 통과한다.
	static final String ENGINE_VERSION = "tingle-rules-1";
	static final int MAX_DOCUMENT_CHARS = 1_000_000;
	private static final int MAX_SURVEY_VERSION = 30;
	private static final int MAX_QUESTION_ID = 50;
	private static final int MAX_TEXT = 1000;
	private static final int MAX_SHORT = 100;
	private static final Set<String> SPECIAL_ANSWERS = Set.of("unknown", "any");

	private static final Map<RuleKind, String> SCHEMAS = Map.of(
		RuleKind.QUESTIONNAIRE, "questionnaire-1",
		RuleKind.MIGRATION, "migration-1",
		RuleKind.MATCHING, "matching-1",
		RuleKind.PRODUCT_POLICY, "product-policy-1");
	private static final Set<String> MATCHING_KEYS = Set.of("ruleVersion", "firmwareRuleId", "hardFilters",
		"traitBands", "priorityDomainWeight", "minDirectionScore", "minCoverage", "combine");
	private static final Set<String> TRAIT_BAND_KEYS = Set.of("low", "high", "tolerancePerStep");
	private static final Set<String> POLICY_SECONDS = Set.of("opportunitySeconds", "counterpartWaitSeconds",
		"proposalResponseSeconds", "leaseSeconds", "presenceGapSeconds");
	private static final Set<String> POLICY_KEYS = Set.of("policyVersion", "firmwarePolicyId", "opportunitySeconds",
		"counterpartWaitSeconds", "proposalResponseSeconds", "leaseSeconds", "presenceGapSeconds",
		"declineCooldownDays");
	private static final long MAX_POLICY_SECONDS = 365L * 86400;
	private static final Set<String> QUESTIONNAIRE_KEYS = Set.of(
		"surveyVersion", "wordingVersion", "questionCount", "questions", "sourceCommit");
	private static final Set<String> QUESTION_KEYS = Set.of("questionId", "ordinal", "part", "text",
		"responseSchema", "options", "allowUnknown", "allowAny", "calculationBinding");
	private static final Set<String> MIGRATION_KEYS = Set.of(
		"fromSurveyVersion", "toSurveyVersion", "preserve", "reconfirm");

	private RuleDocuments() {
	}

	static boolean isRegistered(RuleKind kind, String schemaVersion) {
		return schemaVersion.equals(SCHEMAS.get(kind));
	}

	/**
	 * 형식에 맞지 않는 칸의 위치 목록. 비어 있으면 형식이 맞다.
	 */
	static List<String> problems(RuleKind kind, String version, JsonNode document) {
		List<String> problems = new ArrayList<>();
		if (kind == RuleKind.QUESTIONNAIRE) {
			questionnaire(version, document, problems);
		} else if (kind == RuleKind.MIGRATION) {
			migration(document, problems);
		} else if (kind == RuleKind.MATCHING) {
			matching(version, document, problems);
		} else if (kind == RuleKind.PRODUCT_POLICY) {
			productPolicy(version, document, problems);
		} else {
			problems.add("kind");
		}
		return problems;
	}

	/**
	 * 문항마다 정의대로 답을 받는지 확인하는 회귀 사례. 보기 값은 받아야 하고, "unknown"·"any"는 allowUnknown·allowAny 와
	 * 같아야 하며, 보기에 없는 값은 거절해야 한다. 받는 답 가운데 가장 긴 조합도 답 저장 한도(survey_answer.answer_value)에
	 * 들어가야 한다. 기대와 다른 사례를 "문항 사례 expected=… actual=…" 로 돌려준다.
	 *
	 * @return [전체 사례 수, 기대와 다른 사례 목록]
	 */
	static FixtureResult fixtures(SurveyDefinition definition) {
		int total = 0;
		List<String> failures = new ArrayList<>();
		JsonNodeFactory nodes = JsonNodeFactory.instance;
		for (JsonNode question : definition.questions()) {
			String id = question.get("questionId").asString();
			List<JsonNode> values = question.get("options").valueStream().map(option -> option.get("value")).toList();
			List<ArrayBranch> choices = arrayChoices(question.get("responseSchema"), new ArrayList<>());
			List<Fixture> cases = new ArrayList<>();
			values.forEach(value -> cases.add(new Fixture(value, true)));
			cases.add(new Fixture(nodes.stringNode("unknown"), question.get("allowUnknown").asBoolean()));
			cases.add(new Fixture(nodes.stringNode("any"), question.get("allowAny").asBoolean()));
			cases.add(new Fixture(nodes.stringNode("__not_an_option__"), false));
			for (Fixture fixture : cases) {
				total++;
				JsonNode accepted;
				if (fixture.expected() && values.contains(fixture.value())) {
					// 보기 하나만 고른 답은 앱이 보내는 모양 그대로 받아야 한다(앱이 설문을 열 때 보기마다 확인한다).
					JsonNode single = appSingleChoice(question.get("responseSchema"), fixture.value());
					accepted = accepts(definition, id, single) ? single : null;
				} else {
					// 거절해야 하는 값도 정상 보기로 최소 선택 수를 채운 배열에 넣어 거절되는지 본다(여러 개 고르는 문항).
					accepted = accepts(definition, id, fixture.value()) ? fixture.value()
						: acceptedArrayWith(definition, id, fixture.value(), choices);
				}
				if ((accepted != null) != fixture.expected()) {
					failures.add(id + " " + fixture.value() + " expected=" + verdict(fixture.expected())
						+ " actual=" + verdict(accepted != null));
				} else if (accepted != null && !SurveyAnswer.fitsStorage(accepted)) {
					failures.add(id + " " + accepted + " expected=storable actual=too-long");
				}
			}
			// 응답 규칙이 받는 값(enum·const)은 모두 보기에 있어야 한다. "unknown"·"any"는 배열 밖 단독 답으로만 허용하고,
			// 배열 항목으로는 보기에 있어도 받지 않는다.
			for (AllowedValue allowed : allowedScalars(question.get("responseSchema"), false, new ArrayList<>())) {
				total++;
				boolean special = allowed.value().isString() && SPECIAL_ANSWERS.contains(allowed.value().asString());
				if (special && allowed.inArray()) {
					failures.add(id + " [" + allowed.value() + "] expected=reject actual=accept");
				} else if (!special && !values.contains(allowed.value())) {
					failures.add(id + " " + allowed.value() + " expected=option actual=not-in-options");
				}
			}
			total++;
			for (ArrayBranch branch : choices) {
				total++;
				JsonNode longest = longestPossibleArray(branch);
				if (!SurveyAnswer.fitsStorage(longest)) {
					failures.add(id + " " + longest + " expected=storable actual=too-long");
				}
			}
		}
		return new FixtureResult(total, failures);
	}

	private static List<AllowedValue> allowedScalars(JsonNode schema, boolean inArray, List<AllowedValue> found) {
		schema.path("enum").forEach(value -> found.add(new AllowedValue(value, inArray)));
		if (schema.has("const")) {
			found.add(new AllowedValue(schema.get("const"), inArray));
		}
		schema.path("oneOf").forEach(branch -> allowedScalars(branch, inArray, found));
		if (schema.has("items")) {
			allowedScalars(schema.get("items"), true, found);
		}
		return found;
	}

	// 여러 개 고르는 문항은 type:array(앱이 요구)·항목 허용값(items.enum만)·중복 금지(uniqueItems)·최대 선택 수(maxItems)가
	// 있어야 한다.
	// 그래야 보기 밖 값을 받지 않고 가장 긴 답이 저장 한도에 드는지 확인할 수 있다.
	private static boolean boundedArrays(JsonNode schema) {
		if (schema == null || !schema.isObject()) {
			return true;
		}
		boolean isArray = schema.has("type") || schema.has("items") || schema.has("minItems")
			|| schema.has("maxItems") || schema.has("uniqueItems");
		JsonNode items = schema.get("items");
		boolean itemsLimited = items != null && items.isObject() && items.size() == 1 && items.has("enum");
		if (isArray && !("array".equals(schema.path("type").asString("")) && itemsLimited
			&& schema.path("uniqueItems").asBoolean(false) && schema.has("maxItems"))) {
			return false;
		}
		return schema.path("oneOf").valueStream().allMatch(RuleDocuments::boundedArrays);
	}

	/**
	 * 앱(SurveyWireMapping.wire)이 보기 하나를 고른 답을 만드는 모양: 응답 규칙(또는 그 oneOf 분기) 가운데
	 * type:array 분기가 딱 하나면 [값], 아니면 값 그대로. 앱이 바뀌기 전까지 서버도 같은 모양으로 확인한다.
	 */
	private static JsonNode appSingleChoice(JsonNode schema, JsonNode value) {
		List<JsonNode> branches = schema.has("oneOf") ? schema.get("oneOf").valueStream().toList() : List.of(schema);
		long arrays = branches.stream().filter(branch -> "array".equals(branch.path("type").asString(""))).count();
		return arrays == 1 ? JsonNodeFactory.instance.arrayNode().add(value) : value;
	}

	// 배열 분기마다 항목 허용값(items.enum)과 최대 선택 수. 배열 분기가 없으면 빈 목록.
	private static List<ArrayBranch> arrayChoices(JsonNode schema, List<ArrayBranch> found) {
		if (schema.has("items")) {
			found.add(new ArrayBranch(schema.get("items").path("enum").valueStream().toList(),
				schema.path("maxItems").asInt()));
		}
		schema.path("oneOf").forEach(branch -> arrayChoices(branch, found));
		return found;
	}

	// 여러 개 고르는 문항: 배열 분기마다 그 분기의 허용값을 더해 가며(최소 선택 수까지) 값을 넣은 배열을 받는지 본다.
	// 받는 배열이 있으면 그 배열, 없으면 null.
	private static JsonNode acceptedArrayWith(SurveyDefinition definition, String id, JsonNode value,
		List<ArrayBranch> choices) {
		for (ArrayBranch branch : choices) {
			ArrayNode array = JsonNodeFactory.instance.arrayNode().add(value);
			List<JsonNode> fillers = branch.values().stream().filter(other -> !other.equals(value)).toList();
			for (int i = 0; ; i++) {
				if (accepts(definition, id, array)) {
					return array;
				}
				if (i >= fillers.size()) {
					break;
				}
				array = array.deepCopy().add(fillers.get(i));
			}
		}
		return null;
	}

	// 분기에서 나올 수 있는 가장 긴 답의 상한: 긴 허용값부터 최대 선택 수만큼 고른 배열.
	// ponytail: 분기가 겹쳐 이 조합이 실제로는 거절돼도 상한으로 본다(저장 못 하는 정의를 놓치지 않는 쪽).
	private static JsonNode longestPossibleArray(ArrayBranch branch) {
		List<JsonNode> longestFirst = branch.values().stream()
			// 저장 한도(SurveyAnswer)와 같이 코드 포인트로 센다. UTF-16 길이로 고르면 이모지 보기 때문에 최장 조합을 놓친다.
			.sorted(Comparator.comparingInt((JsonNode value) -> value.toString().codePointCount(0,
				value.toString().length())).reversed()).toList();
		int size = Math.min(branch.maxItems(), longestFirst.size());
		return JsonNodeFactory.instance.arrayNode().addAll(longestFirst.subList(0, size));
	}

	/**
	 * 키를 정렬한 JSON 문자열. 같은 내용이면 칸 순서와 상관없이 같은 문자열·해시가 나온다.
	 */
	static String canonical(JsonNode node, JsonMapper mapper) {
		return mapper.writeValueAsString(sorted(node, JsonNodeFactory.instance));
	}

	static String contentHash(RuleKind kind, String version, String schemaVersion, String canonicalDocument) {
		String content = kind + "\n" + version + "\n" + schemaVersion + "\n" + canonicalDocument;
		return SurveyCatalog.sha256(content.getBytes(StandardCharsets.UTF_8));
	}

	private static JsonNode sorted(JsonNode node, JsonNodeFactory nodes) {
		if (node.isObject()) {
			Map<String, JsonNode> fields = new TreeMap<>();
			node.properties().forEach(entry -> fields.put(entry.getKey(), sorted(entry.getValue(), nodes)));
			ObjectNode result = nodes.objectNode();
			fields.forEach(result::set);
			return result;
		}
		if (node.isArray()) {
			return nodes.arrayNode().addAll(node.valueStream().map(item -> sorted(item, nodes)).toList());
		}
		return node;
	}

	private static boolean accepts(SurveyDefinition definition, String questionId, JsonNode value) {
		try {
			SurveyAnswerValidator.validate(definition, questionId, value);
			return true;
		} catch (BusinessException rejected) {
			return false;
		}
	}

	private static String verdict(boolean accepted) {
		return accepted ? "accept" : "reject";
	}

	private static void questionnaire(String version, JsonNode root, List<String> problems) {
		if (!root.isObject()) {
			problems.add("document");
			return;
		}
		unknownKeys(root, QUESTIONNAIRE_KEYS, "document", problems);
		JsonNode surveyVersion = root.get("surveyVersion");
		require(text(surveyVersion, MAX_SURVEY_VERSION) && surveyVersion.asString().equals(version),
			"document.surveyVersion", problems);
		require(text(root.get("wordingVersion"), MAX_SHORT), "document.wordingVersion", problems);
		require(root.get("sourceCommit") == null || text(root.get("sourceCommit"), MAX_SHORT),
			"document.sourceCommit", problems);
		JsonNode questions = root.get("questions");
		if (questions == null || !questions.isArray() || questions.isEmpty()) {
			problems.add("document.questions");
			return;
		}
		JsonNode count = root.get("questionCount");
		require(count != null && count.isIntegralNumber() && count.asInt() == questions.size(),
			"document.questionCount", problems);
		Set<String> ids = new HashSet<>();
		Set<Integer> ordinals = new HashSet<>();
		for (int i = 0; i < questions.size(); i++) {
			question(questions.get(i), "document.questions[" + i + "]", ids, ordinals, problems);
		}
		// 앱은 표시 순서가 1부터 빈틈없이 이어져야 설문을 연다.
		for (int ordinal = 1; ordinal <= questions.size(); ordinal++) {
			if (!ordinals.contains(ordinal)) {
				problems.add("document.questions.ordinal");
				break;
			}
		}
	}

	private static void question(JsonNode question, String path, Set<String> ids, Set<Integer> ordinals,
		List<String> problems) {
		if (!question.isObject()) {
			problems.add(path);
			return;
		}
		unknownKeys(question, QUESTION_KEYS, path, problems);
		JsonNode id = question.get("questionId");
		require(text(id, MAX_QUESTION_ID) && ids.add(id.asString()), path + ".questionId", problems);
		JsonNode ordinal = question.get("ordinal");
		require(ordinal != null && ordinal.isIntegralNumber() && ordinal.asInt() >= 1
			&& ordinals.add(ordinal.asInt()), path + ".ordinal", problems);
		JsonNode part = question.get("part");
		require(part != null && part.isIntegralNumber() && part.asInt() >= 1, path + ".part", problems);
		require(text(question.get("text"), MAX_TEXT), path + ".text", problems);
		require(text(question.get("calculationBinding"), MAX_SHORT), path + ".calculationBinding", problems);
		require(isBoolean(question.get("allowUnknown")), path + ".allowUnknown", problems);
		require(isBoolean(question.get("allowAny")), path + ".allowAny", problems);
		SurveyAnswerValidator.collectSchemaProblems(question.get("responseSchema"), path + ".responseSchema", problems);
		require(boundedArrays(question.get("responseSchema")), path + ".responseSchema", problems);
		JsonNode options = question.get("options");
		if (options == null || !options.isArray() || options.isEmpty()) {
			problems.add(path + ".options");
			return;
		}
		Set<String> optionValues = new HashSet<>();
		for (int i = 0; i < options.size(); i++) {
			JsonNode option = options.get(i);
			String at = path + ".options[" + i + "]";
			JsonNode value = option.get("value");
			require(option.isObject() && option.size() == 2 && text(option.get("label"), MAX_TEXT)
				&& value != null && (finiteNumber(value) || text(value, MAX_SHORT))
				&& optionValues.add(optionKey(value)), at, problems);
		}
	}

	private static boolean finiteNumber(JsonNode value) {
		return value.isNumber() && Double.isFinite(value.doubleValue());
	}

	// 보기 중복 판단 기준. 앱·서버 검사기처럼 숫자는 값으로(1 = 1.0), 문자열은 그대로 비교하고 숫자와 문자열은 다르다.
	private static String optionKey(JsonNode value) {
		return value.isNumber() ? "n:" + value.decimalValue().stripTrailingZeros().toPlainString()
			: "s:" + value.asString();
	}

	private static void migration(JsonNode root, List<String> problems) {
		if (!root.isObject()) {
			problems.add("document");
			return;
		}
		unknownKeys(root, MIGRATION_KEYS, "document", problems);
		JsonNode from = root.get("fromSurveyVersion");
		JsonNode to = root.get("toSurveyVersion");
		require(text(from, MAX_SURVEY_VERSION), "document.fromSurveyVersion", problems);
		require(text(to, MAX_SURVEY_VERSION) && !to.equals(from), "document.toSurveyVersion", problems);
		// 한 문항이 옮길 문항(preserve)과 다시 답할 문항(reconfirm)에 함께 있으면 안 된다(이관은 preserve 답을 그대로 복사한다).
		Set<String> seen = new HashSet<>();
		for (String key : List.of("preserve", "reconfirm")) {
			JsonNode ids = root.get(key);
			require(ids != null && ids.isArray()
				&& ids.valueStream().allMatch(id -> text(id, MAX_QUESTION_ID) && seen.add(id.asString())),
				"document." + key, problems);
		}
	}

	// 키링 매칭 규칙: 정의 버전은 키링 기록의 matchingRuleVersion, firmwareRuleId 는 펌웨어 KR_MATCH_RULE 과 같은 값이다.
	private static void matching(String version, JsonNode root, List<String> problems) {
		if (!root.isObject()) {
			problems.add("document");
			return;
		}
		unknownKeys(root, MATCHING_KEYS, "document", problems);
		JsonNode ruleVersion = root.get("ruleVersion");
		require(keyringVersion(ruleVersion, MAX_SHORT) && ruleVersion.asString().equals(version),
			"document.ruleVersion", problems);
		require(equalsLong(root.get("firmwareRuleId"), Ter2ProofVerifier.RULE), "document.firmwareRuleId", problems);
		JsonNode filters = root.get("hardFilters");
		Set<String> seen = new HashSet<>();
		require(filters != null && filters.isArray()
			&& filters.valueStream().allMatch(filter -> text(filter, MAX_SHORT) && seen.add(filter.asString())),
			"document.hardFilters", problems);
		JsonNode bands = root.get("traitBands");
		if (bands == null || !bands.isObject()) {
			problems.add("document.traitBands");
		} else {
			unknownKeys(bands, TRAIT_BAND_KEYS, "document.traitBands", problems);
			require(scores(bands.get("low")) && scores(bands.get("high"))
				&& bands.get("low").size() == bands.get("high").size()
				&& java.util.stream.IntStream.range(0, bands.get("low").size())
					.allMatch(i -> bands.get("low").get(i).asInt() <= bands.get("high").get(i).asInt()),
				"document.traitBands", problems);
			require(between(bands.get("tolerancePerStep"), 0, 100, true), "document.traitBands.tolerancePerStep",
				problems);
		}
		require(between(root.get("priorityDomainWeight"), 1, 100, true), "document.priorityDomainWeight", problems);
		require(between(root.get("minDirectionScore"), 0, 100, false), "document.minDirectionScore", problems);
		require(between(root.get("minCoverage"), 0, 1, false), "document.minCoverage", problems);
		require(root.get("combine") != null && "harmonic".equals(root.get("combine").asString(null)),
			"document.combine", problems);
	}

	// 제품 정책: 정의 버전은 키링 기록의 policyVersion, firmwarePolicyId 는 펌웨어 KR_MATCH_POLICY 와 같은 값이다.
	private static void productPolicy(String version, JsonNode root, List<String> problems) {
		if (!root.isObject()) {
			problems.add("document");
			return;
		}
		unknownKeys(root, POLICY_KEYS, "document", problems);
		JsonNode policyVersion = root.get("policyVersion");
		require(keyringVersion(policyVersion, MAX_SURVEY_VERSION) && policyVersion.asString().equals(version),
			"document.policyVersion", problems);
		require(equalsLong(root.get("firmwarePolicyId"), Ter2ProofVerifier.POLICY), "document.firmwarePolicyId",
			problems);
		// 키링 설정 형식(PeerSettingsCodec·Ter2ProofVerifier)이 받는 기한 상한과 같다.
		for (String key : POLICY_SECONDS) {
			require(between(root.get(key), 1, MAX_POLICY_SECONDS, true), "document." + key, problems);
		}
		require(between(root.get("declineCooldownDays"), 1, 3650, true), "document.declineCooldownDays", problems);
	}

	// long 범위를 넘는 정수는 잘리거나 예외(500)가 나지 않게 먼저 거른다.
	private static boolean equalsLong(JsonNode node, long expected) {
		return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() == expected;
	}

	// 키링 기록·워커가 받는 버전 문자(영숫자·.·_·-)만 허용한다. 다른 문자는 게시돼도 쓸 수 없다.
	private static boolean keyringVersion(JsonNode node, int maxLength) {
		return text(node, maxLength) && node.asString().matches("[A-Za-z0-9._-]+");
	}

	private static boolean scores(JsonNode node) {
		return node != null && node.isArray() && !node.isEmpty()
			&& node.valueStream().allMatch(value -> between(value, 0, 100, true));
	}

	private static boolean between(JsonNode node, double low, double high, boolean integral) {
		return node != null && (integral ? node.isIntegralNumber() : node.isNumber())
			&& Double.isFinite(node.doubleValue()) && node.doubleValue() >= low && node.doubleValue() <= high;
	}

	private static void unknownKeys(JsonNode node, Set<String> allowed, String path, List<String> problems) {
		node.propertyNames().stream().filter(key -> !allowed.contains(key))
			.forEach(key -> problems.add(path + "." + key));
	}

	private static boolean text(JsonNode node, int maxLength) {
		return node != null && node.isString() && !node.asString().isBlank() && node.asString().length() <= maxLength;
	}

	private static boolean isBoolean(JsonNode node) {
		return node != null && node.isBoolean();
	}

	private static void require(boolean ok, String path, List<String> problems) {
		if (!ok) {
			problems.add(path);
		}
	}

	private record Fixture(JsonNode value, boolean expected) {
	}

	private record AllowedValue(JsonNode value, boolean inArray) {
	}

	private record ArrayBranch(List<JsonNode> values, int maxItems) {
	}

	record FixtureResult(int total, List<String> failures) {
	}
}
