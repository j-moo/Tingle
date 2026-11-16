package com.e106.tingle.risk;

import java.util.function.ToIntFunction;

import com.e106.tingle.risk.RiskAnalysisTypes.Input;

/**
 * 위험 분석 입력 전체의 토큰 수(또는 그 상한)를 로컬에서 센다. 원격 호출을 하지 않고, 실제보다 작게 세지 않는다.
 * 기본 구현은 바이트 수 상한(ByteBoundRiskTokenizer)이고 실제 토큰 수는 AI 서버가 다시 센다.
 */
@FunctionalInterface
public interface RiskTokenizer extends ToIntFunction<Input> {
}
