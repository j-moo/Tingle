package com.e106.tingle.chat.notice;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Lifecycle notice state owned by one chat_participant.notice_state document. */
public class ChatNoticeJob {
	private final Map<String, Object> document;

	public ChatNoticeJob(long roomId, long userId, String type, Instant runAt) {
		this.document = new HashMap<>();
		document.put("noticeId", UUID.randomUUID().toString());
		document.put("jobType", type);
		document.put("businessKey", key(roomId, userId, type));
		document.put("payload", new HashMap<>(Map.of("roomId", roomId, "userId", userId)));
		document.put("runAt", runAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
		document.put("status", type.equals("FIRST_ENTRY") ? "SUCCEEDED" : "PENDING");
		document.put("attempts", 0);
		if (type.equals("FIRST_ENTRY")) {
			document.put("finishedAt", getRunAt().toString());
		}
	}

	private ChatNoticeJob(Map<String, Object> document) {
		this.document = document;
	}

	static ChatNoticeJob restore(Map<String, Object> document) {
		return new ChatNoticeJob(document);
	}

	Map<String, Object> document() {
		return document;
	}

	public UUID getId() {
		return getPublicId();
	}

	public UUID getPublicId() {
		return UUID.fromString((String)document.get("noticeId"));
	}

	public String getJobType() {
		return (String)document.get("jobType");
	}

	public String getBusinessKey() {
		return (String)document.get("businessKey");
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> getPayload() {
		return (Map<String, Object>)document.get("payload");
	}

	public String getStatus() {
		return (String)document.get("status");
	}

	public Instant getRunAt() {
		return instant("runAt");
	}

	public int getAttempts() {
		return ((Number)document.get("attempts")).intValue();
	}

	public Instant getLeaseUntil() {
		return instant("leaseUntil");
	}

	public String getLastErrorCode() {
		return (String)document.get("lastErrorCode");
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> getResultJson() {
		return (Map<String, Object>)document.get("resultJson");
	}

	public Instant getFinishedAt() {
		return instant("finishedAt");
	}

	public static String key(long roomId, long userId, String type) {
		return "chat-notice:" + roomId + ":" + userId + ":" + type;
	}

	public long roomId() {
		return ((Number)getPayload().get("roomId")).longValue();
	}

	public long userId() {
		return ((Number)getPayload().get("userId")).longValue();
	}

	public void bind(String operation, UUID key) {
		getPayload().putIfAbsent(operation + "Key", key.toString());
	}

	public void claim(Instant now) {
		document.put("status", "RUNNING");
		document.put("leaseUntil", now.plusSeconds(30).toString());
		document.put("attempts", getAttempts() + 1);
	}

	public void finish(String state, String reason, Instant now) {
		document.put("status", state);
		document.put("lastErrorCode", reason);
		document.remove("leaseUntil");
		document.put("finishedAt", now.toString());
	}

	public void retry(String code, Instant now) {
		if (getAttempts() >= 5) {
			finish("FAILED", code, now);
		} else {
			document.put("status", "PENDING");
			document.put("lastErrorCode", code);
			document.remove("leaseUntil");
			document.put("runAt", now.plus(Duration.ofSeconds(30L << Math.min(getAttempts() - 1, 5))).toString());
		}
	}

	public void delivered(String installationBinding, String result) {
		if (getResultJson() == null) {
			document.put("resultJson", new HashMap<String, Object>());
		}
		getResultJson().put(installationBinding, result);
	}

	public boolean alreadyDelivered(String installationBinding) {
		return getResultJson() != null && getResultJson().containsKey(installationBinding);
	}

	private Instant instant(String name) {
		return document.get(name) instanceof String value ? Instant.parse(value) : null;
	}
}
