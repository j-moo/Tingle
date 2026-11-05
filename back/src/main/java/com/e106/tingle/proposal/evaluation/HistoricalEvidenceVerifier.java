package com.e106.tingle.proposal.evaluation;

import com.e106.tingle.proposal.MatchProposal;

/**
 * 실제 확정 판정·당시 원래 A/B·만남/실행 세대·불변 snapshot/inputHash·manifest와
 * 사실/sourceRef/당시 공개 결정을 검증하는 서버 내부 신뢰 경계.
 * UUID/해시의 형식 검사나 현재 프로필로 검증을 대신하지 않는다.
 * 네트워크를 호출하지 않으며 실제 생산자가 없으면 빈 등록을 하지 않는다.
 */
@FunctionalInterface
public interface HistoricalEvidenceVerifier {
	boolean verify(MatchProposal matching, HistoricalEvaluationEvidence evidence);
}
