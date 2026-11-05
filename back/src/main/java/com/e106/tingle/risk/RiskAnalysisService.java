package com.e106.tingle.risk;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Service;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.risk.RiskAnalysisTypes.Kind;
import com.e106.tingle.risk.RiskAnalysisTypes.Manifest;
import com.e106.tingle.risk.RiskAnalysisTypes.Request;
import com.e106.tingle.risk.RiskAnalysisTypes.Response;
import com.e106.tingle.risk.RiskAnalysisTypes.Verdict;

import lombok.RequiredArgsConstructor;

/** 모델 호출은 DB 트랜잭션 밖에서 실행한다. 운영 기본 분석기는 전송을 항상 거부한다. */
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
		}
		return store.finish(claim, result);
	}
}
