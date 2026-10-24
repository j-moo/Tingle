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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 버전별 설문 정의 파일(resources/survey/questionnaires/*.json)을 읽어 둔다.
 * 시작한 회차는 버전 문자열(version_code)로 정의를 고정하므로, 현재 버전을 바꿔도 과거 파일은 지우지 않는다.
 * 원본은 docs/contracts/survey/ 이고 같은 내용이어야 한다(SurveyCatalogTest 가 확인).
 */
@Component
public class SurveyCatalog {

	private final Map<String, SurveyDefinition> byVersion = new HashMap<>();
	private final String currentVersion;
	private final JsonNode metadataSchema;

	public SurveyCatalog(JsonMapper jsonMapper, @Value("${app.survey.current-version}") String currentVersion)
		throws IOException {
		Resource[] files = new PathMatchingResourcePatternResolver()
			.getResources("classpath:survey/questionnaires/*.json");
		for (Resource file : files) {
			byte[] bytes;
			try (InputStream in = file.getInputStream()) {
				bytes = in.readAllBytes();
			}
			SurveyDefinition definition = SurveyDefinition.of(jsonMapper.readTree(bytes), sha256(bytes));
			byVersion.put(definition.surveyVersion(), definition);
		}
		if (!byVersion.containsKey(currentVersion)) {
			throw new IllegalStateException("현재 설문 버전 파일이 없습니다: " + currentVersion);
		}
		this.currentVersion = currentVersion;
		try (InputStream in = new PathMatchingResourcePatternResolver()
			.getResource("classpath:survey/metadata-schema.json").getInputStream()) {
			this.metadataSchema = jsonMapper.readTree(in);
		}
	}

	public SurveyDefinition current() {
		return byVersion.get(currentVersion);
	}

	public Optional<SurveyDefinition> find(String surveyVersion) {
		return Optional.ofNullable(byVersion.get(surveyVersion));
	}

	public JsonNode metadataSchema() {
		return metadataSchema;
	}

	private static String sha256(byte[] bytes) {
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
}
