package com.e106.tingle.settings;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.preference.PreferencesRules;
import com.e106.tingle.settings.dto.SettingsRequest;

import tools.jackson.databind.JsonNode;

/** 본인 경로와 PUT의 두 불리언을 검증하며 누락값을 false로 바꾸지 않는다. */
public final class SettingsRules {

	private SettingsRules() {
	}

	public static void requireOwner(long authenticatedUserId, long userId) {
		PreferencesRules.requireOwner(authenticatedUserId, userId);
	}

	public static SettingsRequest parse(JsonNode body) {
		if (body == null || !body.isObject() || body.size() != 2
			|| !body.has("matchingEnabled") || !body.has("requirePartnerPhoto")
			|| !body.get("matchingEnabled").isBoolean() || !body.get("requirePartnerPhoto").isBoolean()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return new SettingsRequest(body.get("matchingEnabled").booleanValue(),
			body.get("requirePartnerPhoto").booleanValue());
	}
}
