package com.e106.tingle.personality;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.personality.dto.AnalysisRequestResponse;
import com.e106.tingle.personality.dto.AnalysisResultResponse;
import com.e106.tingle.survey.SurveyResponse;
import com.e106.tingle.survey.SurveyResponseRepository;

import lombok.RequiredArgsConstructor;
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
