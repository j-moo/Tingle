package com.e106.tingle.personality;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 개인 성향 분석 설정(application.yml tingle.ai.personal-analysis). AI 주소·서비스 토큰은 공통 app.ai.service 를 쓴다
 * (꺼져 있으면 분석 요청은 PENDING 으로 남는다).
 *
 * @param analysisVersion 새 요청에 쓰는 분석 규칙 버전(personality.analysis_version)
 * @param surveyVersions 이 분석 규칙이 받는 설문 버전. 그 밖의 회차는 409 ANALYSIS_VERSION_UNSUPPORTED
 * @param maxAttempts 한 결과의 최대 실행 횟수(최초 실행 포함)
 * @param retryDelay 일시 오류 뒤 다시 실행하기까지 기다리는 시간
 * @param stuckAfter 실행을 선점한 뒤 이 시간이 지나도 끝나지 않으면 멈춘 것으로 보고 다시 실행한다
 */
@ConfigurationProperties("tingle.ai.personal-analysis")
public record PersonalAnalysisProperties(String analysisVersion, List<String> surveyVersions, int maxAttempts,
	Duration retryDelay, Duration stuckAfter) {
}
