package com.e106.tingle.proposal.explanation;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 신뢰된 과거 원본 어댑터·등록 artifact·규칙 전송이 모두 준비된 경우에만 설명을 제공한다. */
@Component
public class HistoricalRuleExplanationProvider implements RuleExplanationProvider {

	private static final Set<String> CONTEXT_FIELDS = Set.of("reference", "viewer", "evidence",
		"conflicting_source_refs");
	private static final Set<String> SCOPE_FIELDS = Set.of("reference", "viewer", "version",
		"model_evidence_ids", "display_evidence_ids");
	private static final Set<String> EVIDENCE_FIELDS = Set.of("id", "direction", "kind", "topic", "fact");
	private static final Set<String> TOPICS = Set.of("contact", "planning", "activity", "experience");
	private static final Set<String> KINDS = Set.of("MUTUAL_PREFERENCE_FIT", "SHARED_REPORTED_BEHAVIOR",
		"ONE_WAY_PREFERENCE_FIT", "DIFFERENCE");
	private final HistoricalExplanationSource source;
	private final ExplanationArtifactCatalog catalog;
	private final RuleExplanationTransport transport;
	private final ObjectMapper mapper;

	@Autowired
	public HistoricalRuleExplanationProvider(ObjectProvider<HistoricalExplanationSource> source,
		ExplanationArtifactCatalog catalog, ObjectProvider<RuleExplanationTransport> transport, ObjectMapper mapper) {
		this(source.getIfUnique(), catalog, transport.getIfUnique(), mapper);
	}

	public HistoricalRuleExplanationProvider(HistoricalExplanationSource source, ExplanationArtifactCatalog catalog,
		RuleExplanationTransport transport, ObjectMapper mapper) {
		this.source = source;
		this.catalog = catalog;
		this.transport = transport;
		this.mapper = mapper;
	}

	@Override
	public ExplanationStatus explain(Input input) {
		try {
			if (source == null || transport == null || input == null || input.proposalId() <= 0 || input.viewerId() <= 0
				|| input.attemptId() == null || input.inputFingerprint() == null
				|| !input.inputFingerprint().matches("[a-f0-9]{64}")) {
				return unavailable();
			}
			Optional<HistoricalExplanationSource.Source> found = source.find(input);
			if (found.isEmpty() || found.get().viewerId() != input.viewerId()) {
				return unavailable();
			}
			JsonNode manifest = mapper.valueToTree(found.get().manifest());
			JsonNode bundle = mapper.valueToTree(found.get().contextBundle());
			if (!catalog.supports(manifest) || !validBundle(input, manifest, bundle)) {
				return unavailable();
			}
			ExplanationStatus result = transport.explain(new RuleExplanationTransport.Request(input.attemptId(),
				input.proposalId(), manifest, bundle)).orElseGet(RuleExplanationProvider::unavailable);
			// 규칙 설명은 버전이 없어야 한다. GMS 생성 설명의 버전 형식은 ExplanationStatus가 이미 확인했다.
			boolean generated = ExplanationStatus.GENERATED.equals(result.source());
			boolean rule = ExplanationStatus.RULE_FALLBACK.equals(result.source()) && result.promptVersion() == null;
			if (!"SUCCEEDED".equals(result.status()) || !(rule || generated) || !result.evidenceKeys().isEmpty()) {
				return unavailable();
			}
			return result;
		} catch (RuntimeException exception) {
			// 원문·모델 응답·키/토큰·내부 실패 사유를 공개 응답이나 로그에 복사하지 않는다.
			return unavailable();
		}
	}

	private static boolean validBundle(Input input, JsonNode manifest, JsonNode bundle) {
		if (!ExplanationArtifactCatalog.hasFields(bundle, Set.of("context", "scope"))) {
			return false;
		}
		JsonNode context = bundle.get("context");
		JsonNode scope = bundle.get("scope");
		if (!ExplanationArtifactCatalog.hasFields(context, CONTEXT_FIELDS)
			|| !ExplanationArtifactCatalog.hasFields(scope, SCOPE_FIELDS)) {
			return false;
		}
		JsonNode reference = context.get("reference");
		if (!ExplanationArtifactCatalog.hasFields(reference, Set.of("encounter_id", "evaluation_ref", "version_ref"))
			|| !reference.get("encounter_id").isIntegralNumber() || !reference.get("encounter_id").canConvertToLong()
			|| reference.get("encounter_id").longValue() != input.proposalId()
			|| !reference.path("evaluation_ref").isString()
			|| !input.inputFingerprint().equals(reference.path("evaluation_ref").asString())
			|| !reference.path("version_ref").equals(manifest.get("releaseId"))
			|| !reference.equals(scope.get("reference")) || !context.get("viewer").equals(scope.get("viewer"))
			|| !context.get("viewer").isString() || !Set.of("A", "B").contains(context.get("viewer").asString())
			|| !text(scope.get("version"), 200)) {
			return false;
		}
		JsonNode evidence = context.get("evidence");
		if (!evidence.isArray() || evidence.isEmpty() || evidence.size() > 12) {
			return false;
		}
		Set<String> ids = new HashSet<>();
		for (JsonNode record : evidence) {
			if (!ExplanationArtifactCatalog.hasFields(record, Set.of("reference", "source_ref", "evidence"))
				|| !reference.equals(record.get("reference")) || !text(record.get("source_ref"), 200)) {
				return false;
			}
			JsonNode item = record.get("evidence");
			if (!ExplanationArtifactCatalog.hasFields(item, EVIDENCE_FIELDS) || !text(item.get("id"), 4)
				|| !item.get("id").asString().matches("e[1-9][0-9]{0,2}") || !ids.add(item.get("id").asString())
				|| !text(item.get("topic"), 40) || !TOPICS.contains(item.get("topic").asString())
				|| !text(item.get("kind"), 40) || !KINDS.contains(item.get("kind").asString())
				|| !text(item.get("direction"), 10) || !text(item.get("fact"), 500)) {
				return false;
			}
			String direction = item.get("direction").asString();
			boolean oneWay = "ONE_WAY_PREFERENCE_FIT".equals(item.get("kind").asString());
			if (oneWay ? !Set.of("A_TO_B", "B_TO_A").contains(direction) : !"BOTH".equals(direction)) {
				return false;
			}
		}
		Set<String> display = strings(scope.get("display_evidence_ids"));
		// 모델(GMS)로 보내도 되는 근거는 화면에 보여 주기로 한 근거의 일부여야 한다. 그 밖의 근거는 밖으로 나가지 않는다.
		Set<String> model = strings(scope.get("model_evidence_ids"));
		return display != null && !display.isEmpty() && ids.containsAll(display)
			&& model != null && display.containsAll(model)
			&& strings(context.get("conflicting_source_refs")) != null;
	}

	private static Set<String> strings(JsonNode values) {
		if (!values.isArray()) {
			return null;
		}
		Set<String> result = new HashSet<>();
		for (JsonNode value : values) {
			if (!text(value, 200) || !result.add(value.asString())) {
				return null;
			}
		}
		return result;
	}

	private static boolean text(JsonNode value, int maximum) {
		return value != null && value.isString() && !value.asString().isBlank() && value.asString().length() <= maximum;
	}

	private static ExplanationStatus unavailable() {
		return RuleExplanationProvider.unavailable();
	}
}
