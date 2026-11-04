package com.e106.tingle.personality;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.personality.dto.AnalysisRequestResponse;
import com.e106.tingle.personality.dto.AnalysisResultResponse;
import com.e106.tingle.survey.SurveyResponse;
import com.e106.tingle.survey.SurveyResponseRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 성향 분석 요청 접수·결과 조회. 접수는 PENDING 줄만 남기고, 분석은 PersonalAnalysisWorker 가 따로 실행한다.
 */
@Service
@RequiredArgsConstructor
public class PersonalAnalysisService {

	private static final String NEW_RETRY_STATE = "{\"attempts\":0}";

	private final PersonalityRepository personalities;
	private final SurveyResponseRepository surveys;
	private final PersonalAnalysisProperties properties;
	private final JsonMapper jsonMapper;
	private final Clock clock;

	/**
	 * 본인 완료 회차의 분석을 요청한다. 같은 회차·분석 규칙 버전의 줄이 있으면 상태와 상관없이 그 줄을 돌려준다.
	 */
	@Transactional
	public AnalysisRequestResponse request(long userId, long sessionId) {
		// 회차 행을 잠가 같은 회차의 동시 요청이 unique 위반 없이 한 줄로 모이게 한다.
		SurveyResponse survey = surveys.findOwnedForUpdate(sessionId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		if (!survey.isCompleted()) {
			throw new BusinessException(ErrorCode.SESSION_NOT_COMPLETED);
		}
		if (!properties.surveyVersions().contains(survey.getVersionCode())) {
			throw new BusinessException(ErrorCode.ANALYSIS_VERSION_UNSUPPORTED);
		}
		String version = properties.analysisVersion();
		Personality personality = personalities.findBySurveyIdAndAnalysisVersion(sessionId, version)
			.orElseGet(() -> personalities.save(Personality.pending(sessionId, version, NEW_RETRY_STATE)));
		// 재시도까지 끝난 실패(다음 재시도 시각 없음)는 다시 요청하면 처음부터 다시 시도한다. 설문을 새로 하지 않아도 된다.
		if (personality.getStatus() == Personality.AnalysisStatus.FAILED
			&& !jsonMapper.readTree(personality.getResult()).has("nextRetryAt")) {
			personality.retryState(Personality.AnalysisStatus.PENDING, NEW_RETRY_STATE);
		}
		return new AnalysisRequestResponse(personality.getId(), sessionId, version, personality.getStatus().name());
	}

	/**
	 * 가입 단계 조회에 넣을 복구 정보. 가장 최근 완료 회차와, 그 회차의 지금 분석 버전 결과(아직 요청 전이면 null).
	 * 결과가 null 이면 같은 회차로 분석 요청을 다시 보내면 된다(중복 줄은 생기지 않음).
	 */
	@Transactional(readOnly = true)
	public Recovery recovery(long userId) {
		return surveys.findFirstByUserIdAndCompletedAtIsNotNullOrderByCompletedAtDescIdDesc(userId)
			.map(survey -> new Recovery(survey.getId(), personalities
				.findBySurveyIdAndAnalysisVersion(survey.getId(), properties.analysisVersion())
				.map(p -> new AnalysisRequestResponse(p.getId(), p.getSurveyId(), p.getAnalysisVersion(),
					p.getStatus().name()))
				.orElse(null)))
			.orElse(new Recovery(null, null));
	}

	public record Recovery(Long sessionId, AnalysisRequestResponse analysis) {
	}

	/**
	 * 공용 작업 조회(GET /api/jobs)용 상태. 본인 결과가 아니면 없는 결과와 같은 404.
	 * 작업기가 선점해 기한 안에 실행 중이면 RUNNING, 자동 재시도를 기다리는 FAILED 는 아직 끝나지 않았으므로 PENDING 이다.
	 */
	@Transactional(readOnly = true)
	public String jobStatus(long userId, long resultId) {
		Personality personality = personalities.findById(resultId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		surveys.findByIdAndUserId(personality.getSurveyId(), userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		Personality.AnalysisStatus status = personality.getStatus();
		if (status != Personality.AnalysisStatus.PENDING && status != Personality.AnalysisStatus.FAILED) {
			return status.name();
		}
		// PENDING·FAILED 의 result 는 재시도 정보다(attempts·nextRetryAt·lastErrorCode).
		JsonNode retryAt = personality.getResult() == null ? null
			: jsonMapper.readTree(personality.getResult()).get("nextRetryAt");
		if (status == Personality.AnalysisStatus.FAILED) {
			return retryAt == null ? "FAILED" : "PENDING";
		}
		// PENDING 의 nextRetryAt 은 선점 기한이다. 기한이 지난 줄은 멈춤 복구로 다시 실행되기를 기다린다.
		boolean running = retryAt != null && retryAt.isString() && clock.instant().isBefore(
			Instant.parse(retryAt.asString()));
		return running ? "RUNNING" : "PENDING";
	}

	/** 본인 결과만 돌려준다. 남의 결과와 없는 결과는 같은 404. */
	@Transactional(readOnly = true)
	public AnalysisResultResponse get(long userId, long resultId) {
		Personality personality = personalities.findById(resultId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		SurveyResponse survey = surveys.findByIdAndUserId(personality.getSurveyId(), userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		return AnalysisResultResponse.of(personality, survey.getVersionCode(),
			personality.getResult() == null ? null : jsonMapper.readTree(personality.getResult()));
	}
}
