package com.e106.tingle.icebreaker;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
public class IcebreakerQuestions {
	private static final Pattern UNSAFE = Pattern.compile(
		"(?i)(https?://|www\\.|[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.[\\p{L}]{2,}"
			+ "|(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)|[\\w.+-]+@[\\w.-]+|\\d[\\d -]{7,}\\d"
			+ "|연락처|전화번호|카카오톡|종교|흡연|성희롱|송금|계좌번호)");
	// AI가 승인한 GMS 프롬프트 버전 = "icebreakers-gms-" + 프롬프트 SHA-256 앞 16자(ai-server/app/icebreakers/prompts.py).
	// 접두어만 맞는 임의 값은 받지 않는다. AI 프롬프트가 바뀌면 이 값도 같이 바꿔야 한다.
	static final String GMS_PROMPT_VERSION = "icebreakers-gms-ffb273caf96e6059";
	private final ResourceLoader resources;
	private final ObjectMapper mapper;
	private final IcebreakerProperties properties;

	public IcebreakerQuestions(ResourceLoader resources, ObjectMapper mapper, IcebreakerProperties properties) {
		this.resources = resources;
		this.mapper = mapper;
		this.properties = properties;
	}

	public IcebreakerGateway.Generated fallback() {
		try (InputStream input = resources.getResource(properties.generalQuestionsLocation()).getInputStream()) {
			byte[] bytes = input.readNBytes(65537);
			if (bytes.length > 65536) {
				return null;
			}
			IcebreakerGateway.Generated result = mapper.readValue(bytes, IcebreakerGateway.Generated.class);
			return "GENERAL_FALLBACK".equals(result.source()) ? validate(result) : null;
		} catch (IOException | RuntimeException exception) {
			return null;
		}
	}

	public IcebreakerGateway.Generated validate(IcebreakerGateway.Generated value) {
		return validate(value, null);
	}

	public IcebreakerGateway.Generated validate(IcebreakerGateway.Generated value, String commonTopicKey) {
		if (commonTopicKey != null && !IcebreakerTopics.supportedKey(commonTopicKey)) {
			return null;
		}
		if (value == null || value.questions() == null || value.questions().size() != 3
			|| value.source() == null
			|| !Set.of("GENERATED", "GENERAL_FALLBACK", "COMMON_TOPIC_TEMPLATE").contains(value.source())
			|| value.promptVersion() == null || value.promptVersion().isBlank()
			|| value.promptVersion().length() > 100) {
			return null;
		}
		if ("COMMON_TOPIC_TEMPLATE".equals(value.source())
			&& (commonTopicKey == null || !"q040-template-v1".equals(value.promptVersion()))) {
			return null;
		}
		// 공통 주제 요청의 GENERATED는 AI가 승인한 GMS 버전만 받는다. 주제를 무시한 옛 응답은 거절한다.
		if (commonTopicKey != null && "GENERATED".equals(value.source())
			&& !GMS_PROMPT_VERSION.equals(value.promptVersion())) {
			return null;
		}
		Set<String> normalized = new HashSet<>();
		for (String question : value.questions()) {
			if (question == null) {
				return null;
			}
			String trimmed = question.strip();
			int length = trimmed.codePointCount(0, trimmed.length());
			String canonical = Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
				.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
			// 폭 0 문자 같은 서식문자(Cf)는 NFKC로도 안 지워져 연락처 사이에 끼워 금지어 검사를 피하므로 함께 거절한다.
			if (length < 1 || length > 200 || trimmed.codePoints().anyMatch(
				c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
				|| UNSAFE.matcher(canonical).find() || !normalized.add(canonical)) {
				return null;
			}
		}
		List<String> questions = value.questions().stream().map(String::strip).toList();
		return new IcebreakerGateway.Generated(questions, value.source(), value.promptVersion());
	}
}
