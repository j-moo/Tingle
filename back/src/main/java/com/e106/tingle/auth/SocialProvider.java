package com.e106.tingle.auth;

import java.util.Arrays;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

public enum SocialProvider {
	GOOGLE,
	KAKAO,
	NAVER;

	/**
	 * URL 경로의 소문자 이름(google, kakao, naver)을 변환한다. 대문자나 다른 값은 INVALID_PROVIDER.
	 */
	public static SocialProvider fromPath(String path) {
		return Arrays.stream(values())
			.filter(provider -> provider.name().toLowerCase().equals(path))
			.findFirst()
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PROVIDER));
	}
}
