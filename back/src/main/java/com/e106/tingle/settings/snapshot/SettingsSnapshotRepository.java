package com.e106.tingle.settings.snapshot;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettingsSnapshotRepository extends JpaRepository<SettingsSnapshot, Long> {

	Optional<SettingsSnapshot> findByUserIdAndSettingsVersion(long userId, long settingsVersion);

	// 종교를 null 로, 해시를 0 64자로 바꾸고 지운 시각(DB 시각)·사유를 남긴다(처음 지운 시각·사유 유지).
	// 원래 해시를 남기면 남은 입력에 종교 후보 6개를 넣어 보는 것만으로 지운 값을 알아낼 수 있다.
	// 0 해시는 어떤 입력의 해시와도 맞지 않아 이 사본은 근거로 쓰이지 않는다. 종교 값이 남은 사본만 바꾸므로 반복해도 같다.
	String REDACT_RELIGION = """
		UPDATE settings_snapshot s
		SET input_json = CAST(jsonb_set(CAST(s.input_json AS jsonb), '{profile,religion}',
				CAST('null' AS jsonb)) AS text),
			input_hash = repeat('0', 64),
			redacted_at = COALESCE(s.redacted_at, statement_timestamp()),
			redaction_reason = COALESCE(s.redaction_reason, :reason)
		WHERE jsonb_typeof(CAST(s.input_json AS jsonb) #> '{profile,religion}') = 'string'
		""";

	/** 회원의 모든 과거 사본에서 종교를 지운다. 바꾼 사본 수. */
	@Modifying(flushAutomatically = true)
	@Query(value = REDACT_RELIGION + " AND s.user_id = :userId", nativeQuery = true)
	int redactReligion(@Param("userId") long userId, @Param("reason") String reason);

	/**
	 * 모든 회원에서 마지막 종교 철회 시각 이전에 만든 사본의 종교를 지운다. 이 기능 전(또는 배포 중 이전 버전)에 철회돼
	 * 사본이 남은 경우를 정리한다. 재동의 뒤 새로 넣은 종교 사본은 남긴다. 바꾼 사본 수.
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = REDACT_RELIGION + """
		AND s.captured_at <= (SELECT max(c.recorded_at) FROM user_consents c WHERE c.user_id = s.user_id
			AND c.consent_type = 'PROFILE_RELIGION' AND NOT c.agreed)
		""", nativeQuery = true)
	int redactReligionBeforeWithdrawal(@Param("reason") String reason);
}
