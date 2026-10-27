package com.e106.tingle.user.dto;

import java.time.Instant;
import java.util.UUID;

import com.e106.tingle.user.OnboardingStatus;

/**
 * 가입 단계 조회 응답. 앱을 다시 켰을 때 어느 화면으로 갈지 정한다.
 * 설문 진행도(surveySessionId~nextQuestionId)는 현재 버전 설문 기준이다.
 * 설정 반영 버전 필드는 통합 명세의 필수 키라 지금도 보내며, 키링 동기화 기능 전까지는 임시값(0·null)이다.
 *
 * @param nextStep 다음에 마칠 가입 단계. 로그인 응답의 onboardingStatus 와 같은 값이다
 * @param signupCompleted 한 번이라도 모든 가입 단계를 마친 적이 있는지 (app_users.signup_completed_at)
 * @param onboardingCompleted 지금 모든 단계를 마친 상태(READY)인지. 약관 버전이 오르면 다시 false 가 될 수 있다
 * @param matchingReady 새 매칭을 시작할 수 있는지. 지금은 onboardingCompleted 와 같다
 * @param surveySessionId 현재 버전 설문의 작성 중 회차. 없으면 null
 * @param answeredCount 응답을 저장한 문항 수
 * @param questionCount 현재 버전 설문의 전체 문항 수
 * @param nextQuestionId 다음 미응답 문항. 회차가 없으면 첫 문항, 모두 응답했으면 null
 * @param requiredSettingsVersion 서버가 요구하는 최신 설정 변경 번호
 * @param acknowledgedSettingsVersion 기기에 반영됐다고 확인한 설정 변경 번호. 없으면 null
 * @param serverTime 서버의 현재 시각. 기기 시계 보정용
 */
public record OnboardingDetailResponse(
	OnboardingStatus nextStep,
	boolean signupCompleted,
	boolean onboardingCompleted,
	boolean matchingReady,
	UUID surveySessionId,
	int answeredCount,
	int questionCount,
	String nextQuestionId,
	long requiredSettingsVersion,
	Long acknowledgedSettingsVersion,
	Instant serverTime
) {
}
