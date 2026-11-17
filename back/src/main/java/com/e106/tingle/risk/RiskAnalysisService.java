package com.e106.tingle.risk;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Service;

import com.e106.tingle.global.ai.AiCallException;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.risk.RiskAnalysisTypes.Kind;
import com.e106.tingle.risk.RiskAnalysisTypes.Manifest;
import com.e106.tingle.risk.RiskAnalysisTypes.Request;
import com.e106.tingle.risk.RiskAnalysisTypes.Response;
import com.e106.tingle.risk.RiskAnalysisTypes.Verdict;

import lombok.RequiredArgsConstructor;

/** 모델 호출은 DB 트랜잭션 밖에서 실행한다. 승인된 Release 가 없으면 전송하지 않는다. */
@Service
@RequiredArgsConstructor
public class RiskAnalysisService {

	private final RiskAnalysisGateway gateway;
	private final RiskManifestCatalog manifests;
	private final RiskAnalysisStore store;
	private final RiskVerdicts verdicts;
	private final Clock clock;

	public Response analyze(Request request) {
		gateway.requireAvailable();
		var replay = store.replay(request);
		if (replay.isPresent()) {
			return replay.get();
		}
		if (!manifests.supports(request.manifest())) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		RiskAnalysisStore.Claim claim = store.claim(request);
		if (claim.cached() != null) {
			return claim.cached();
		}
		return execute(claim, false);
	}

	public boolean analyzeNext(long roomId, Manifest configured) {
		gateway.requireAvailable();
		if (!manifests.supports(configured)) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		RiskAnalysisStore.Claim claim = store.claimNext(roomId, configured);
		if (claim == null) {
			return false;
		}
		execute(claim, true);
		return true;
	}

	private Response execute(RiskAnalysisStore.Claim claim, boolean automatic) {
		Request request = claim.request();
		Verdict result;
		boolean retryable = true;
		try {
			if (automatic && (!store.canTransmit(claim) || !manifests.supports(request.manifest()))) {
				throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
			}
			Verdict response = automatic
				? gateway.analyze(request, claim.input(), Duration.between(clock.instant(), claim.deadlineAt()))
				: gateway.analyze(request, claim.input());
			result = verdicts.validate(response, claim.input());
			if (!manifests.supports(request.manifest())) {
				throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
			}
		} catch (RuntimeException exception) {
			// 공급자 오류·원응답·대화 본문을 로그나 영속 상태로 복사하지 않는다.
			result = new Verdict(Kind.FAILED, List.of(), null, "unavailable", "unavailable");
			// AI와 합의한 계약: 400(입력 거절)·422(정책 미확정)는 같은 입력을 다시 보내도 결과가 같아 1회 후 종결.
			// 503·429·시간 초과는 기존대로 최대 3회 재시도한다.
			retryable = !(exception instanceof AiCallException call && call.httpStatus() != null
				&& (call.httpStatus() == 400 || call.httpStatus() == 422));
		}
		return store.finish(claim, result, retryable);
	}
}
