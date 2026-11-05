package com.e106.tingle.safety;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 회원 행 잠금 안에서 세 안전 명령의 영수증을 관계 소유 상태에 보존한다. */
@Repository
@RequiredArgsConstructor
public class SafetyReceipts {

	private final JdbcTemplate jdbc;
	private final JsonMapper mapper;

	public String hash(Object request) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(mapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required", exception);
		}
	}

	public <T> Optional<T> find(long userId, UUID key, String command, String requestHash, Class<T> resultType) {
		List<String> saved = jdbc.query("select request_state->>cast(? as text) "
			+ "from matching_participant where user_id=?", (rs, row) -> rs.getString(1), receiptKey(key), userId)
			.stream().filter(Objects::nonNull).toList();
		if (saved.isEmpty()) {
			return Optional.empty();
		}
		if (saved.size() != 1) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		Receipt receipt = mapper.readValue(saved.getFirst(), Receipt.class);
		if (!command.equals(receipt.command()) || !requestHash.equals(receipt.requestHash())) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		return Optional.of(mapper.readValue(receipt.response(), resultType));
	}

	public void save(long matchingId, long userId, UUID key, String command, String requestHash, Object response) {
		String receipt = mapper.writeValueAsString(new Receipt(command, requestHash,
			mapper.writeValueAsString(response)));
		int updated = jdbc.update("update matching_participant set request_state=coalesce(request_state,'{}'::jsonb) "
			+ "|| jsonb_build_object(cast(? as text),cast(? as text)) where matching_id=? and user_id=?",
			receiptKey(key), receipt, matchingId, userId);
		if (updated != 1) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
	}

	private String receiptKey(UUID key) {
		return "safety:" + key;
	}

	private record Receipt(String command, String requestHash, String response) {
	}
}
