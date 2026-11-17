package com.e106.tingle.proposal.explanation;

import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * 매칭 이유 요청에 싣는 버전 정보(manifest) 11필드의 형식 확인.
 * 서버에 등록한 descriptor·artifact 파일과 대조하던 단계는 없앴다(S15P31E106-395).
 * 버전 정보는 만남 판정 때 저장한 근거에서 오므로 파일 사본과 다시 맞출 필요가 없다.
 */
final class ExplanationManifest {

	static final Set<String> MANIFEST_FIELDS = Set.of("releaseId", "surveyVersion", "wordingVersion",
		"scoringVersion", "typeModelVersion", "analysisVersion", "typeReferenceVersion", "matchingVersion",
		"productPolicyVersion", "engineVersion", "contentHash");

	private ExplanationManifest() {
	}

	static boolean validManifest(JsonNode manifest) {
		if (!hasFields(manifest, MANIFEST_FIELDS)) {
			return false;
		}
		for (String key : MANIFEST_FIELDS) {
			JsonNode value = manifest.get(key);
			if (!value.isString() || value.asString().isBlank() || value.asString().length() > 100) {
				return false;
			}
		}
		try {
			String release = manifest.get("releaseId").asString();
			return UUID.fromString(release).toString().equals(release)
				&& manifest.get("contentHash").asString().matches("[a-f0-9]{64}");
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	static boolean hasFields(JsonNode node, Set<String> fields) {
		if (node == null || !node.isObject()) {
			return false;
		}
		return node.size() == fields.size() && fields.stream().allMatch(node::has);
	}
}
