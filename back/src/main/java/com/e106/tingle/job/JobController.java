package com.e106.tingle.job;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.job.dto.JobResponse;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;

import lombok.RequiredArgsConstructor;

/**
 * 본인 비동기 작업 조회. 설문 단계에서도 분석 진행을 볼 수 있게 분석 API와 같은 가입 단계부터 열고,
 * 설명·시작 질문은 각 도메인의 열람 검사가 그대로 적용된다.
 */
@RestController
@RequiredArgsConstructor
public class JobController {

	private final JobService service;

	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	@GetMapping("/api/jobs/{jobId}")
	public ResponseEntity<JobResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String jobId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.get(Long.parseLong(jwt.getSubject()), jobId));
	}
}
