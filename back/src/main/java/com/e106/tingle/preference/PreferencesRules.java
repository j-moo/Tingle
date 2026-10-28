package com.e106.tingle.preference;

import java.util.Set;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.preference.dto.PreferencesRequest;

import tools.jackson.databind.JsonNode;

/**
 * 희망 조건 API의 입력·본인 경로 검증. 저장·ETag·스냅샷 정책을 결정하지 않는다.
 */
public final class PreferencesRules {

	private static final Set<String> FIELDS = Set.of(
		"desiredAgeMode", "desiredAgeMin", "desiredAgeMax");
	private static final int MIN_AGE = 19;

	private PreferencesRules() {
	}

	/**
	 * 저장소 접근 전에 호출한다. 경로 회원 번호는 인증 근거로 사용하지 않는다.
	 */
	public static void requireOwner(long authenticatedUserId, long userId) {
		if (userId < 1) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		if (authenticatedUserId != userId) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
	}

	/**
	 * PUT의 필수 nullable 키는 생략할 수 없다. 문자열·실수·불리언을 정수로 변환하지 않는다.
	 */
	public static PreferencesRequest parse(JsonNode body) {
		if (body == null || !body.isObject() || body.size() != FIELDS.size()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		for (String field : FIELDS) {
			if (!body.has(field)) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST);
			}
		}
		AgePreferenceMode mode = parseMode(body.get("desiredAgeMode"));
		Integer min = parseAge(body.get("desiredAgeMin"));
		Integer max = parseAge(body.get("desiredAgeMax"));
		if (mode == AgePreferenceMode.ANY && (min != null || max != null)) {
			throw new BusinessException(ErrorCode.INVALID_AGE_RANGE);
		}
		if (mode == AgePreferenceMode.RANGE && (min == null || max != null && max < min)) {
			throw new BusinessException(ErrorCode.INVALID_AGE_RANGE);
		}
		return new PreferencesRequest(mode, min, max);
	}

	private static AgePreferenceMode parseMode(JsonNode node) {
		if (node.isString()) {
			for (AgePreferenceMode mode : AgePreferenceMode.values()) {
				if (mode.name().equals(node.asString())) {
					return mode;
				}
			}
		}
		throw new BusinessException(ErrorCode.INVALID_REQUEST);
	}

	private static Integer parseAge(JsonNode node) {
		if (node.isNull()) {
			return null;
		}
		if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < MIN_AGE) {
			throw new BusinessException(ErrorCode.INVALID_AGE_RANGE);
		}
		return node.intValue();
	}
}
