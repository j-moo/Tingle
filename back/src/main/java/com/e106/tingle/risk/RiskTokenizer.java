package com.e106.tingle.risk;

import java.util.function.ToIntFunction;

import com.e106.tingle.risk.RiskAnalysisTypes.Input;

/** 승인된 모델의 로컬 tokenizer. 전체 입력을 계산하며 원격 호출이나 문자 수 추정으로 대체하지 않는다. */
@FunctionalInterface
public interface RiskTokenizer extends ToIntFunction<Input> {
}
