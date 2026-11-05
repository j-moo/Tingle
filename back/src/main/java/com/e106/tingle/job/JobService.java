package com.e106.tingle.job;

import java.util.Set;

import org.springframework.stereotype.Service;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.icebreaker.IcebreakerStore;
import com.e106.tingle.job.dto.JobResponse;
import com.e106.tingle.personality.PersonalAnalysisService;
import com.e106.tingle.proposal.explanation.ExplanationJournal;
import com.e106.tingle.proposal.explanation.ExplanationTransactions;

import lombok.RequiredArgsConstructor;

/**
 * 본인 비동기 작업 조회. 작업 표(background_jobs)를 따로 두지 않고, jobId 의 종류-번호로 각 도메인의 상태 줄을 찾는다.
 * 소유 검사는 매번 각 도메인의 결과 조회와 같은 규칙으로 한다(남의 작업·없는 작업·잘못된 번호는 같은 404).
 */
@Service
@RequiredArgsConstructor
public class JobService {

	private static final Set<String> FINISHED = Set.of("SUCCEEDED", "INSUFFICIENT", "FAILED");

	private final PersonalAnalysisService analyses;
	private final ExplanationTransactions explanations;
	private final IcebreakerStore icebreakers;

	public JobResponse get(long userId, String jobId) {
		Kind kind = null;
		long id = 0;
		int dash = jobId.lastIndexOf('-');
		try {
			kind = Kind.valueOf(jobId.substring(0, dash));
			id = Long.parseLong(jobId.substring(dash + 1));
		} catch (IllegalArgumentException | IndexOutOfBoundsException e) {
			// 형식이 틀린 번호도 없는 작업과 같은 404로 응답한다.
		}
		// "ANALYSIS-012"·"ANALYSIS-+12" 같은 다른 표기는 받지 않는다(번호 하나에 표기 하나).
		if (kind == null || id < 1 || !jobId.equals(kind.name() + "-" + id)) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		String status = switch (kind) {
			case ANALYSIS -> analyses.jobStatus(userId, id);
			case MATCH_EXPLANATION -> explanationStatus(id, userId);
			case ICEBREAKERS -> icebreakers.jobStatus(id, userId);
		};
		boolean failed = "FAILED".equals(status);
		// 분석만 실패 후 같은 회차로 다시 요청하면 처음부터 다시 시도한다. 설명·시작 질문은 각 요청 API와 같이 false.
		return new JobResponse(jobId, kind.name(), status, id, failed ? ErrorCode.SERVICE_UNAVAILABLE.name() : null,
			failed && kind == Kind.ANALYSIS, FINISHED.contains(status) ? 0 : 1);
	}

	// 설명은 요청 처리 안에서 바로 실행된다. 접수 확정(acceptedAt) 뒤의 PENDING 은 실행 중이다.
	private String explanationStatus(long proposalId, long userId) {
		ExplanationJournal.Run run = explanations.current(proposalId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		return "PENDING".equals(run.status()) && run.acceptedAt() != null ? "RUNNING" : run.status();
	}

	enum Kind {
		ANALYSIS,
		MATCH_EXPLANATION,
		ICEBREAKERS
	}
}
