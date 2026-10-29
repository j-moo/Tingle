package com.e106.tingle.user;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.settings.snapshot.SettingsChangeCoordinator;
import com.e106.tingle.settings.snapshot.SettingsSnapshotJson;
import com.e106.tingle.survey.SurveyCatalog;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 성공 분석의 적용 포인터와 설정 세대를 함께 전환한다. 계산 결과의 전송 형식·READY를 승인하지 않는다. */
@Service
@RequiredArgsConstructor
public class ProfileAnalysisApplicationService {

	private final UserRepository users;
	private final ProfileRepository profiles;
	private final SettingsChangeCoordinator changes;
	private final JdbcTemplate jdbc;
	private final SurveyCatalog surveys;
	private final SettingsSnapshotJson canonicalJson;
	private final JsonMapper mapper;

	/** expectedActiveResultId는 계산을 시작할 때 확인한 적용 포인터이며, 재시도에 새 값으로 바꾸지 않는다. */
	@Transactional
	public boolean apply(long userId, long resultId, Long expectedActiveResultId) {
		if (userId <= 0 || resultId <= 0 || expectedActiveResultId != null && expectedActiveResultId <= 0) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		User user = users.findByIdForUpdate(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		changes.lockIfPresent(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
		Profile profile = profiles.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_INCOMPLETE));
		if (Objects.equals(profile.getActivePersonalityId(), resultId)) {
			return false;
		}
		if (!Objects.equals(profile.getActivePersonalityId(), expectedActiveResultId)) {
			throw new BusinessException(ErrorCode.PRECONDITION_FAILED);
		}
		AnalysisSource candidate = requireSource(resultId, userId, ErrorCode.INVALID_REQUEST);
		requireInput(candidate);
		if (profile.getActivePersonalityId() != null) {
			AnalysisSource active = requireSource(profile.getActivePersonalityId(), userId,
				ErrorCode.SERVICE_UNAVAILABLE);
			if (candidate.completedAt().isBefore(active.completedAt())) {
				throw new BusinessException(ErrorCode.PRECONDITION_FAILED);
			}
			if (candidate.completedAt().equals(active.completedAt())) {
				// 같은 회차의 다른 분석 버전을 문자열/숫자 ID 순서로 최신이라고 가정하지 않는다.
				throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
			}
		}
		requireLatestCompletedSuccess(userId, resultId, candidate.completedAt());
		profile.applyPersonality(resultId);
		changes.advanceAndCapture(userId);
		return true;
	}

	private AnalysisSource requireSource(long resultId, long userId, ErrorCode error) {
		List<Map<String, Object>> rows = jdbc.queryForList("select s.user_id,s.completed_at,s.survey_version,"
			+ "a.analysis_version,a.status::text as status,a.result::text as result from personality a "
			+ "join survey s on s.id=a.survey_id where a.id=?", resultId);
		if (rows.size() != 1) {
			throw new BusinessException(error);
		}
		Map<String, Object> row = rows.getFirst();
		if (!(row.get("user_id") instanceof Number owner) || owner.longValue() != userId
			|| !(row.get("completed_at") instanceof Timestamp completed) || !"SUCCEEDED".equals(row.get("status"))
			|| !(row.get("analysis_version") instanceof String version) || version.isBlank()) {
			throw new BusinessException(error);
		}
		return new AnalysisSource(completed.toInstant(), (String)row.get("survey_version"),
			(String)row.get("result"));
	}

	private void requireInput(AnalysisSource source) {
		if (source.surveyVersion() == null || surveys.find(source.surveyVersion()).isEmpty()) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		try {
			String canonical = canonicalJson.canonicalize(source.result());
			if (mapper.readTree(canonical).isEmpty()) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST);
			}
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
	}

	private void requireLatestCompletedSuccess(long userId, long resultId, Instant completedAt) {
		String successes = " from personality a join survey s on s.id=a.survey_id "
			+ "where s.user_id=? and a.status='SUCCEEDED' and s.completed_at";
		Long newer = jdbc.queryForObject("select count(*)" + successes + ">?", Long.class,
			userId, Timestamp.from(completedAt));
		if (newer == null || newer > 0) {
			throw new BusinessException(ErrorCode.PRECONDITION_FAILED);
		}
		Long simultaneous = jdbc.queryForObject("select count(*)" + successes + "=? and a.id<>?", Long.class,
			userId, Timestamp.from(completedAt), resultId);
		if (simultaneous == null || simultaneous > 0) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
	}

	private record AnalysisSource(Instant completedAt, String surveyVersion, String result) {
	}
}
