package com.e106.tingle.settings.snapshot;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 내부 저장용 정규화 v1. 공개 프로토콜/JCS 서명 형식으로 사용하지 않는다. */
@Component
public class SettingsSnapshotJson {

	private static final int MAX_BYTES = 262144;
	private static final TypeReference<Map<String, Object>> OBJECT_TYPE = new TypeReference<>() {
	};
	private final JsonMapper mapper = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
		.enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
		.build();

	public String canonicalize(String input) {
		if (input == null || input.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
			throw new IllegalArgumentException("Invalid snapshot input size");
		}
		try {
			Map<String, Object> object = mapper.readValue(input, OBJECT_TYPE);
			if (object == null) {
				throw new IllegalArgumentException("Snapshot input must be an object");
			}
			String canonical = mapper.writeValueAsString(normalize(object));
			if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
				throw new IllegalArgumentException("Invalid canonical snapshot size");
			}
			return canonical;
		} catch (RuntimeException exception) {
			// 입력 원문이나 parser 오류 메시지를 업무 로그/응답으로 전달하지 않는다.
			throw new IllegalArgumentException("Invalid snapshot JSON object");
		}
	}

	public String hash(String canonical) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	private Object normalize(Object value) {
		if (value instanceof Map<?, ?> object) {
			Map<String, Object> sorted = new TreeMap<>();
			object.forEach((key, child) -> sorted.put(requireUnicode((String)key), normalize(child)));
			return sorted;
		}
		if (value instanceof List<?> array) {
			return array.stream().map(this::normalize).toList();
		}
		if (value instanceof Number number) {
			return new BigDecimal(number.toString()).stripTrailingZeros();
		}
		if (value instanceof String text) {
			return requireUnicode(text);
		}
		return value;
	}

	private static String requireUnicode(String text) {
		for (int index = 0; index < text.length(); index++) {
			char current = text.charAt(index);
			if (Character.isHighSurrogate(current)) {
				if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
					throw new IllegalArgumentException("Invalid snapshot Unicode");
				}
				index++;
			} else if (Character.isLowSurrogate(current)) {
				throw new IllegalArgumentException("Invalid snapshot Unicode");
			}
		}
		return text;
	}
}
