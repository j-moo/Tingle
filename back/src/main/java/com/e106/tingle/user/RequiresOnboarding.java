package com.e106.tingle.user;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 API를 호출할 수 있는 가장 이른 가입 단계. 붙이지 않은 /api/** 는 READY(가입 완료)가 필요하다.
 * 예: PROFILE_REQUIRED 면 프로필 단계와 그 뒤 모든 단계에서 호출할 수 있다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresOnboarding {

	OnboardingStatus value();
}
