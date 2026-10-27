package com.e106.tingle.chat.notice;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "background_jobs", uniqueConstraints = {
	@UniqueConstraint(name = "uq_background_jobs_1", columnNames = "business_key"),
	@UniqueConstraint(name = "uq_background_jobs_public_id", columnNames = "public_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatNoticeJob {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "job_id")
	private Long id;
	private UUID publicId;
	@Column(nullable = false, columnDefinition = "text")
	private String jobType;
	@Column(nullable = false, columnDefinition = "text")
	private String businessKey;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> payload;
	@Column(nullable = false, columnDefinition = "text")
	private String status;
	@Column(nullable = false)
	private Instant runAt;
	@Column(nullable = false)
	private int attempts;
	private Instant leaseUntil;
	@Column(columnDefinition = "text")
	private String lastErrorCode;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private Map<String, Object> resultJson;
	private Instant finishedAt;

	public ChatNoticeJob(long roomId, long userId, String type, Instant runAt) {
		this.publicId = UUID.randomUUID();
		this.jobType = type;
		this.businessKey = key(roomId, userId, type);
		this.payload = new HashMap<>(Map.of("roomId", roomId, "userId", userId));
		this.runAt = runAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		this.status = type.equals("FIRST_ENTRY") ? "SUCCEEDED" : "PENDING";
		this.finishedAt = type.equals("FIRST_ENTRY") ? runAt : null;
	}

	public static String key(long roomId, long userId, String type) {
		return "chat-notice:" + roomId + ":" + userId + ":" + type;
	}

	public long roomId() {
		return ((Number)payload.get("roomId")).longValue();
	}

	public long userId() {
		return ((Number)payload.get("userId")).longValue();
	}

	public void bind(String operation, UUID key) {
		payload.putIfAbsent(operation + "Key", key.toString());
	}

	public void claim(Instant now) {
		status = "RUNNING";
		leaseUntil = now.plusSeconds(30);
		attempts++;
	}

	public void finish(String state, String reason, Instant now) {
		status = state;
		lastErrorCode = reason;
		leaseUntil = null;
		finishedAt = now;
	}

	public void retry(String code, Instant now) {
		if (attempts >= 5) {
			finish("FAILED", code, now);
		} else {
			status = "PENDING";
			lastErrorCode = code;
			leaseUntil = null;
			runAt = now.plus(Duration.ofSeconds(30L << Math.min(attempts - 1, 5)));
		}
	}

	public void delivered(String installationBinding, String result) {
		if (resultJson == null) {
			resultJson = new HashMap<>();
		}
		resultJson.put(installationBinding, result);
	}

	public boolean alreadyDelivered(String installationBinding) {
		return resultJson != null && resultJson.containsKey(installationBinding);
	}
}
