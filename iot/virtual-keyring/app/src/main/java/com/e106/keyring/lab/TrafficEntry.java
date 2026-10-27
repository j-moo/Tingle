package com.e106.keyring.lab;

import java.time.Instant;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.e106.keyring.core.DemoProfile;
import com.e106.keyring.core.Encounter;
import com.e106.keyring.core.Packet;

/** Durable discovery, radio transmission, authenticated reception or encounter confirmation. */
public final class TrafficEntry {
	public final long id;
	public final long timestamp;
	public final String direction;
	public final String peerId;
	public final Packet packet;
	public final String messageKey;
	public final String code;
	public final String detail;
	public final String eventId;

	public TrafficEntry(long id, long timestamp, String direction, String peerId, Packet packet, String messageKey) {
		this(id, timestamp, direction, peerId, packet, messageKey, "", "", null);
	}

	public TrafficEntry(long id, long timestamp, String direction, String peerId, Packet packet, String messageKey,
		String code, String detail, String eventId) {
		this.id = id;
		this.timestamp = timestamp;
		this.direction = direction;
		this.peerId = peerId;
		this.packet = packet;
		this.messageKey = messageKey;
		this.code = code;
		this.detail = detail;
		this.eventId = eventId;
	}

	public String categoryName() {
		switch (direction) {
			case "PROCESS":
				return "처리 과정";
			case "DISCOVERY":
				return "기기 발견";
			case "TX":
				return messageKey.startsWith("legacy:") ? "전송 요청 (이전 기록)" : "송신 시작";
			case "SAVED":
				return "만남 저장 완료";
			default:
				return "수신";
		}
	}

	public String typeName() {
		if (packet == null) {
			return code;
		}
		String[] names = {"UNKNOWN", "HELLO", "PING", "PONG", "PROPOSE", "READY", "COMMIT", "STORED", "ERROR",
			"NEW_REQUEST", "MATCH_DATA", "MATCH_RESULT", "RECHECK", "RECHECK_OK", "PREPARE", "PREPARED",
			"COMMIT", "STORED", "STORED_RECEIPT", "PREPARE_REFERENCE", "RECHECK_BUSY",
			"PROFILE_BROADCAST", "LOCAL_RECORD"};
		return names[packet.type];
	}

	public JSONObject toJson() throws JSONException {
		JSONObject value = new JSONObject();
		value.put("direction", direction);
		value.put("messageId", messageKey.startsWith("legacy:") ? JSONObject.NULL : messageKey);
		value.put("recordedAt", Instant.ofEpochMilli(timestamp).toString());
		value.put("peerDeviceId", peerId);
		value.put("type", typeName());
		JSONObject data = new JSONObject();
		if (packet == null) {
			data.put("code", code);
			data.put("detail", detail);
			data.put("event_id", eventId == null ? JSONObject.NULL : eventId);
			value.put("data", data);
			return value;
		}
		if (packet.profile != null) {
			data = profileJson(packet.profile);
		}
		if (packet.context != null) {
			data.put("input_context_id", packet.context.toString());
			if (packet.type == Packet.MATCH_RESULT) {
				data.put("matching_allowed", packet.allowed);
				data.put("matching_rule_version", DemoProfile.RULE);
			}
		}
		if (packet.peer != null) {
			data.put("deviceId", packet.peer.deviceId.toString());
			data.put("snapshotId", packet.peer.snapshotId.toString());
			data.put("settingsVersion", packet.peer.settingsVersion);
			data.put("ready", packet.peer.ready);
			data.put("extendedAdvertising", packet.extended);
		}
		if (packet.token != null) {
			data.put("token", packet.token.toString());
		}
		if (packet.encounter != null) {
			Encounter event = packet.encounter;
			data.put("eventId", event.eventId.toString());
			data.put("independent", event.independent);
			data.put("peerStorageVerified", false);
			data.put("deviceA", event.deviceA.toString());
			data.put("deviceB", event.deviceB.toString());
			data.put("snapshotA", event.snapshotA.toString());
			data.put("snapshotB", event.snapshotB.toString());
			data.put("settingsVersionA", event.versionA);
			data.put("settingsVersionB", event.versionB);
			data.put("occurredAt", Instant.ofEpochMilli(event.occurredAt).toString());
			if (event.hasProfiles()) {
				data.put("event_id", event.eventId.toString());
				data.put("user_a_id", event.profileA.userId.toString());
				data.put("user_b_id", event.profileB.userId.toString());
				data.put("keyring_a_id", event.deviceA.toString());
				data.put("keyring_b_id", event.deviceB.toString());
				data.put("matched_at", Instant.ofEpochMilli(event.occurredAt).toString());
				data.put("expires_at", Instant.ofEpochMilli(event.expiresAt).toString());
				data.put("policy_version", event.profileA.policyVersion);
				data.put("matching_rule_version", event.profileA.ruleVersion);
				data.put("input_a", profileJson(event.profileA));
				data.put("input_b", profileJson(event.profileB));
			}
		}
		if (packet.type == Packet.PREPARE_REFERENCE) {
			data.put("event_id", packet.token.toString());
			data.put("recheck_token", packet.recheckToken.toString());
			data.put("occurredAt", Instant.ofEpochMilli(packet.occurredAt).toString());
			data.put("snapshots", "authenticated MATCH_DATA referenced by input_context_id");
		} else if (packet.error != null) {
			data.put("error", packet.error);
		}
		value.put("data", data);
		return value;
	}

	public String summary() {
		if (packet == null) {
			return "STATUS".equals(code) ? detail : FlowText.describe(code);
		}
		if ("DISCOVERY".equals(direction)) {
			return "같은 그룹의 키링을 발견했어요.";
		}
		if ("SAVED".equals(direction)) {
			return "만남 데이터를 저장하고 양쪽 저장을 확인했어요.";
		}
		boolean sent = "TX".equals(direction);
		switch (packet.type) {
			case Packet.PROFILE_BROADCAST:
			case Packet.MATCH_DATA:
				return sent ? "내 임시 성향·회원·기기·설정을 보내요." : "상대의 임시 성향·회원·기기·설정을 받았어요.";
			case Packet.MATCH_RESULT:
				return (sent ? "내 판정을 보내요: " : "상대 판정을 받았어요: ") + (packet.allowed ? "통과" : "보류");
			case Packet.RECHECK:
			case Packet.RECHECK_OK:
				return sent ? "근접·현재 조건 재확인 데이터를 보내요." : "근접·현재 조건 재확인 데이터를 받았어요.";
			case Packet.RECHECK_BUSY:
				return sent ? "다른 상대 처리 중임을 알려요." : "상대가 다른 만남을 처리 중이라 차례를 다시 기다려요.";
			case Packet.PREPARE:
			case Packet.PREPARE_REFERENCE:
			case Packet.PREPARED:
			case Packet.FINALIZE:
			case Packet.DURABLE:
			case Packet.RECEIPT:
				return (sent ? "만남 저장 단계 송신: " : "만남 저장 단계 수신: ") + typeName();
			case Packet.HELLO:
				return sent ? "주변 키링에 발견용 데이터를 보내요." : "상대 키링의 발견용 데이터를 받았어요.";
			case Packet.PING:
				return sent ? "통신 확인 요청을 보내요." : "상대가 통신 확인을 요청했어요.";
			case Packet.PONG:
				return sent ? "통신 확인 응답을 보내요." : "통신 확인 응답을 받았어요.";
			case Packet.PROPOSE:
				return sent ? "새 만남 데이터를 보내요." : "새 만남 데이터를 받았어요.";
			case Packet.READY:
				return sent ? "만남 저장 준비 응답을 보내요." : "상대의 저장 준비 응답을 받았어요.";
			case Packet.COMMIT:
				return sent ? "확정한 만남 데이터를 보내요." : "확정한 만남 데이터를 받았어요.";
			case Packet.STORED:
				return sent ? "만남 저장 완료 응답을 보내요." : "상대의 만남 저장 완료 응답을 받았어요.";
			case Packet.NEW_REQUEST:
				return sent ? "다음 시험 만남을 요청해요." : "다음 시험 만남 요청을 받았어요.";
			default:
				return "통신 확인이 필요해요: " + packet.error;
		}
	}

	public static JSONObject profileJson(DemoProfile profile) throws JSONException {
		JSONObject data = new JSONObject();
		data.put("service_id", com.e106.keyring.core.AdvertisementCodec.SERVICE.toString());
		data.put("user_id", profile.userId.toString());
		data.put("keyring_id", profile.keyring.deviceId.toString());
		data.put("matching_enabled", profile.matchingEnabled);
		data.put("notification_enabled", profile.notificationEnabled);
		data.put("has_photo", profile.hasPhoto);
		data.put("require_partner_photo", profile.requirePhoto);
		data.put("age", profile.age);
		data.put("gender", "DEMO_UNSPECIFIED");
		data.put("desired_age", "DEMO_ANY_ADULT");
		JSONArray own = new JSONArray();
		JSONArray desired = new JSONArray();
		for (byte trait : profile.personality()) {
			own.put((int)trait);
		}
		for (byte trait : profile.desired()) {
			desired.put((int)trait);
		}
		data.put("personality", own);
		data.put("desired_personality", desired);
		data.put("sync_version", profile.keyring.settingsVersion);
		data.put("settings_snapshot_id", profile.keyring.snapshotId.toString());
		data.put("policy_version", profile.policyVersion);
		data.put("matching_rule_version", profile.ruleVersion);
		data.put("synthetic", true);
		return data;
	}
}
