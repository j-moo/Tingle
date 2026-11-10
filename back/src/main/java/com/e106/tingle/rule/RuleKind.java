package com.e106.tingle.rule;

/**
 * 규칙 정의 종류. 통합 명세의 9종을 그대로 두지만, 서버가 검사·사용할 수 있는 형식이 정해진 것은
 * QUESTIONNAIRE·MIGRATION·MATCHING·PRODUCT_POLICY 뿐이다(RuleDocuments). 나머지는 형식이 합의될 때까지 422 POLICY_UNRESOLVED.
 */
public enum RuleKind {
	QUESTIONNAIRE,
	WORDING,
	SCORING,
	TYPE_MODEL,
	ANALYSIS,
	TYPE_REFERENCE,
	MATCHING,
	PRODUCT_POLICY,
	MIGRATION
}
