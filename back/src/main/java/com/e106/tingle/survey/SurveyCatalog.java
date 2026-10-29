package com.e106.tingle.survey;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 버전별 설문 정의 파일(resources/survey/questionnaires/*.json)을 읽어 둔다.
 * 시작한 회차는 버전 문자열(survey_version)로 정의를 고정하므로, 현재 버전을 바꿔도 과거 파일은 지우지 않는다.
 * 원본은 ai-server/reference/survey-20261021/ 이고 같은 내용이어야 한다(SurveyCatalogTest 가 확인).
 */
@Component
public class SurveyCatalog {

	private final Map<String, SurveyDefinition> byVersion = new HashMap<>();
	private final Map<String, MigrationRule> migrations = new HashMap<>();
	private final String currentVersion;

	public SurveyCatalog(JsonMapper jsonMapper, @Value("${app.survey.current-version}") String currentVersion)
		throws IOException {
		for (Resource file : resources("classpath*:survey/questionnaires/*.json")) {
			byte[] bytes = read(file);
			SurveyDefinition definition = SurveyDefinition.of(jsonMapper.readTree(bytes), sha256(bytes));
			byVersion.put(definition.surveyVersion(), definition);
		}
		if (!byVersion.containsKey(currentVersion)) {
			throw new IllegalStateException("현재 설문 버전 파일이 없습니다: " + currentVersion);
		}
		this.currentVersion = currentVersion;
		for (Resource file : resources("classpath*:survey/migrations/*.json")) {
			byte[] bytes = read(file);
			MigrationRule rule = MigrationRule.of(jsonMapper.readTree(bytes), sha256(bytes));
			rule.requireConsistentWith(byVersion.get(rule.fromSurveyVersion()), byVersion.get(rule.toSurveyVersion()));
			migrations.put(rule.fromSurveyVersion() + "\n" + rule.toSurveyVersion(), rule);
		}
	}

	/**
	 * from 버전 회차를 to 버전으로 옮기는 이관 규칙 파일. 없으면(같은 버전 포함) 자동 이관하지 않는다.
	 */
	public Optional<MigrationRule> migration(String fromSurveyVersion, String toSurveyVersion) {
		return Optional.ofNullable(migrations.get(fromSurveyVersion + "\n" + toSurveyVersion));
	}

	public SurveyDefinition current() {
		return byVersion.get(currentVersion);
	}

	public Optional<SurveyDefinition> find(String surveyVersion) {
		return Optional.ofNullable(byVersion.get(surveyVersion));
	}

	// classpath*: 는 main·test 등 여러 classpath 위치의 파일을 모두 읽는다.
	static Resource[] resources(String pattern) throws IOException {
		return new PathMatchingResourcePatternResolver().getResources(pattern);
	}

	static byte[] read(Resource file) throws IOException {
		try (InputStream in = file.getInputStream()) {
			return in.readAllBytes();
		}
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * 한 버전의 설문 정의. questions 는 파일의 문항을 그대로 앱에 내려준다.
	 */
	public record SurveyDefinition(String surveyVersion, String wordingVersion, String contentHash,
		List<JsonNode> questions, List<String> questionIds) {

		static SurveyDefinition of(JsonNode root, String contentHash) {
			List<JsonNode> questions = root.get("questions").valueStream()
				.sorted((a, b) -> Integer.compare(a.get("ordinal").asInt(), b.get("ordinal").asInt()))
				.toList();
			if (questions.isEmpty() || questions.size() != root.get("questionCount").asInt()) {
				throw new UncheckedIOException(new IOException("문항 수가 questionCount 와 다릅니다: "
					+ root.get("surveyVersion").asString()));
			}
			return new SurveyDefinition(root.get("surveyVersion").asString(), root.get("wordingVersion").asString(),
				contentHash, questions, questions.stream().map(q -> q.get("questionId").asString()).toList());
		}

		public int questionCount() {
			return questions.size();
		}
	}

	/**
	 * 설문 버전 이관 규칙 파일(resources/survey/migrations/*.json). 문항 ID를 임의로 짝짓지 않고 파일에 적힌 것만 따른다.
	 *
	 * @param preserve 두 버전에서 뜻이 같아 답을 그대로 옮기는 문항 ID
	 * @param reconfirm 새 버전에서 다시 답해야 하는 문항 ID
	 * @param policyHash 규칙 파일 내용의 SHA-256. 미리보기와 실제 이관이 같은 규칙인지 확인한다
	 */
	public record MigrationRule(String fromSurveyVersion, String toSurveyVersion, Set<String> preserve,
		List<String> reconfirm, String policyHash) {

		static MigrationRule of(JsonNode root, String policyHash) {
			return new MigrationRule(root.get("fromSurveyVersion").asString(), root.get("toSurveyVersion").asString(),
				Set.copyOf(strings(root.get("preserve"))), strings(root.get("reconfirm")), policyHash);
		}

		private static List<String> strings(JsonNode array) {
			return array.valueStream().map(JsonNode::asString).toList();
		}

		// 잘못된 규칙 파일로 서버가 뜨지 않게 한다(없는 버전·문항, 같은 버전끼리).
		void requireConsistentWith(SurveyDefinition from, SurveyDefinition to) {
			if (from == null || to == null || from == to || !from.questionIds().containsAll(preserve)
				|| !to.questionIds().containsAll(preserve) || !to.questionIds().containsAll(reconfirm)) {
				throw new IllegalStateException("이관 규칙 파일이 설문 파일과 맞지 않습니다: "
					+ fromSurveyVersion + " -> " + toSurveyVersion);
			}
		}
	}
}
