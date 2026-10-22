package com.e106.tingle.agreement;

/**
 * AI 동의는 최초 가입 때 필수지만, 가입 후 철회로 제안·매칭을 막지 않는다. 채팅 권한은 별도로 검사한다.
 */
public final class AgreementPolicy {

	private AgreementPolicy() {
	}

	public static boolean requiredForAccess(String type, boolean required, boolean signupCompleted) {
		return required && (!signupCompleted || !"AI_RISK_DETECTION".equals(type));
	}
}
