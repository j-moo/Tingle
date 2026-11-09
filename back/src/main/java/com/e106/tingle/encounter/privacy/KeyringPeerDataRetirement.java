package com.e106.tingle.encounter.privacy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

/** 탈퇴 트랜잭션에 참가한다. 선택 기능 OFF여도 과거에 보존한 양쪽 원문을 정리한다. */
@Service
@RequiredArgsConstructor
public class KeyringPeerDataRetirement {
	private final JdbcTemplate jdbc;

	/** 호출자는 먼저 회원 행을 잠근다. 기록 식별자는 재접수 방지 tombstone으로 남긴다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void retireForWithdrawal(long userId, Instant completedAt) {
		if (userId <= 0 || completedAt == null || completedAt.getEpochSecond() <= 0) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		jdbc.queryForList("select id from keyring where owner_user_id=? order by id for update", Long.class, userId);
		if (exists("keyring_peer_ownership")) {
			var starts = jdbc.queryForList("select valid_from from keyring_peer_ownership "
				+ "where owner_user_id=? and valid_until is null order by keyring_id,owner_generation for update",
				Timestamp.class, userId);
			if (starts.stream().anyMatch(start -> start == null || start.toInstant().isAfter(completedAt))) {
				throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
			}
			// 실제 탈퇴 시각으로만 닫으며 과거의 시작 시각과 이미 닫힌 구간은 바꾸지 않는다.
			jdbc.update("update keyring_peer_ownership set valid_until=? "
				+ "where owner_user_id=? and valid_until is null", Timestamp.from(completedAt), userId);
		}
		if (exists("keyring_peer_encounter")) {
			jdbc.queryForList("select receipt_id from keyring_peer_encounter "
				+ "where (owner_user_id=? or peer_user_id=?) and not retired order by receipt_id for update",
				UUID.class, userId, userId);
			jdbc.update("update keyring_peer_encounter set retired=true,raw_payload=null,envelope_hash=null,"
				+ "receipt_json=null,peer_credential=null where (owner_user_id=? or peer_user_id=?) and not retired",
				userId, userId);
		}
	}

	private boolean exists(String table) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, table));
	}
}
