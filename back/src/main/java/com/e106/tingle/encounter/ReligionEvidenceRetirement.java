package com.e106.tingle.encounter;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 철회한 입력에 의존하는 만남 세대의 원문·파생 해시를 폐기하고 중복/발행 식별자만 남긴다. */
@Service
@RequiredArgsConstructor
public class ReligionEvidenceRetirement {

	public static final String TOMBSTONE = "{\"retired\":\"PROFILE_RELIGION_WITHDRAWN\"}";
	private static final String INTAKE = "encounter:intake";
	private static final String INPUT = "encounter:verified-input";
	private final JdbcTemplate jdbc;
	private final JsonMapper json;

	@Transactional
	public void retire(long userId) {
		// 다른 참여자의 users 잠금을 추가하지 않는다. writer는 양쪽 users를 잡은 뒤 matching을 잡는다.
		jdbc.queryForList("select id from users where id=? for update", Long.class, userId);
		Set<Long> versions = new HashSet<>(jdbc.queryForList("select settings_version from settings_snapshot "
			+ "where user_id=? and redaction_reason='PROFILE_RELIGION_WITHDRAWN'", Long.class, userId));
		if (versions.isEmpty()) {
			return;
		}
		List<Long> ids = jdbc.queryForList("select id from matching where user_low_id=? or user_high_id=? order by id",
			Long.class, userId, userId);
		for (long id : ids) {
			retireMatching(id, userId, versions);
		}
	}

	private void retireMatching(long id, long userId, Set<Long> versions) {
		List<String> evaluations = jdbc.query("select evaluation::text from matching where id=? for update",
			(rs, row) -> rs.getString(1), id);
		if (evaluations.isEmpty()) {
			return;
		}
		List<Participant> participants = jdbc.query("select user_id,request_state::text from matching_participant "
			+ "where matching_id=? order by user_id for update", (rs, row) -> new Participant(rs.getLong(1),
			object(rs.getString(2))), id);
		String current = currentGeneration(participants);
		Set<String> retired = new HashSet<>();
		boolean currentAffected = contains(object(evaluations.getFirst()), -1, userId, versions);
		for (Participant participant : participants) {
			for (Map.Entry<String, JsonNode> entry : participant.state().properties()) {
				if (entry.getKey().startsWith("encounter:")
					&& contains(decoded(entry.getValue()), participant.user(), userId, versions)) {
					String generation = generation(entry.getKey(), decoded(entry.getValue()), current);
					if (generation == null) {
						throw new IllegalStateException("Encounter generation is missing during retirement");
					}
					retired.add(generation);
				}
			}
		}
		if (currentAffected && current != null) {
			retired.add(current);
		}
		currentAffected |= current != null && retired.contains(current);
		if (retired.isEmpty() && !currentAffected) {
			return;
		}
		for (Participant participant : participants) {
			ObjectNode state = participant.state().deepCopy();
			for (Map.Entry<String, JsonNode> entry : participant.state().properties()) {
				String key = entry.getKey();
				if (!key.startsWith("encounter:")) {
					continue;
				}
				String generation = generation(key, decoded(entry.getValue()), current);
				if (generation == null || !retired.contains(generation) || key.equals(INTAKE)) {
					continue;
				}
				if (key.equals(INPUT) || key.equals(EncounterEvaluationWorker.WORKFLOW_KEY)) {
					state.remove(key);
				} else {
					// receipt/proposal/archive 키는 남겨 동일 사건 재접수와 재발행을 막는다.
					state.put(key, TOMBSTONE);
				}
			}
			for (String generation : retired) {
				state.put("encounter:retired:" + generation, TOMBSTONE);
			}
			jdbc.update("update matching_participant set request_state=cast(? as jsonb),"
				+ "encounter_signature=case when ? then null else encounter_signature end "
				+ "where matching_id=? and user_id=?", json.writeValueAsString(state), currentAffected,
				id, participant.user());
		}
		if (currentAffected) {
			// 판정/설명에도 입력 해시와 원문에서 파생된 근거가 들어갈 수 있다. 제안·응답·채팅 상태는 유지한다.
			jdbc.update("update matching set evaluation=null where id=?", id);
		}
	}

	private String currentGeneration(List<Participant> participants) {
		for (Participant participant : participants) {
			JsonNode intake = decoded(participant.state().path(INTAKE));
			if (intake.path("generation").isString()) {
				return intake.path("generation").asString();
			}
			JsonNode work = decoded(participant.state().path(EncounterEvaluationWorker.WORKFLOW_KEY));
			if (work.path("generationId").isString()) {
				return work.path("generationId").asString();
			}
		}
		return null;
	}

	private String generation(String key, JsonNode value, String current) {
		for (String prefix : List.of("encounter:archive:", "encounter:input:", "encounter:evaluation:",
			"encounter:proposal:", "encounter:retired:")) {
			if (key.startsWith(prefix)) {
				return key.substring(prefix.length());
			}
		}
		if (value.path("receipt").path("generationId").isString()) {
			return value.path("receipt").path("generationId").asString();
		}
		if (value.path("generationId").isString()) {
			return value.path("generationId").asString();
		}
		return key.equals(INPUT) || key.equals(EncounterEvaluationWorker.WORKFLOW_KEY) ? current : null;
	}

	private boolean contains(JsonNode value, long owner, long userId, Set<Long> versions) {
		JsonNode node = decoded(value);
		if (node.isObject()) {
			long subject = node.path("userId").isIntegralNumber() ? node.path("userId").asLong() : owner;
			if (subject == userId && node.path("settingsVersion").isIntegralNumber()
				&& versions.contains(node.path("settingsVersion").asLong())) {
				return true;
			}
			if (subject == userId && node.path("snapshotId").isString() && node.path("inputHash").isString()
				&& !node.hasNonNull("settingsVersion") && !node.hasNonNull("inputSourceRef")) {
				// 구형 근거는 철회 사본과 무관함을 입증할 세대·출처가 없다. 원래부터 재검증 불가인 근거를 폐기한다.
				return true;
			}
			for (Map.Entry<String, JsonNode> child : node.properties()) {
				if (contains(child.getValue(), subject, userId, versions)) {
					return true;
				}
			}
		} else if (node.isArray()) {
			for (JsonNode child : node) {
				if (contains(child, owner, userId, versions)) {
					return true;
				}
			}
		} else if (node.isString()) {
			for (long version : versions) {
				if (node.asString().equals("settings_snapshot:" + userId + ":" + version)) {
					return true;
				}
			}
		}
		return false;
	}

	private ObjectNode object(String value) {
		if (value == null) {
			return json.createObjectNode();
		}
		JsonNode node = json.readTree(value);
		if (!(node instanceof ObjectNode object)) {
			throw new IllegalStateException("Encounter evidence is not an object");
		}
		return object;
	}

	private JsonNode decoded(JsonNode value) {
		if (value.isString() && (value.asString().stripLeading().startsWith("{")
			|| value.asString().stripLeading().startsWith("["))) {
			return json.readTree(value.asString());
		}
		return value;
	}

	private record Participant(long user, ObjectNode state) {
	}
}
