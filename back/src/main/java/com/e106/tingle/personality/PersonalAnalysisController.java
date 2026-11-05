package com.e106.tingle.personality;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.personality.dto.AnalysisRequestResponse;
import com.e106.tingle.personality.dto.AnalysisResultResponse;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.RequiresOnboarding;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * 개인 성향 분석 요청·결과 조회. 설문 API와 같은 가입 단계부터 쓸 수 있다(설문 단계에서 분석까지 마쳐야 하므로).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PersonalAnalysisController {

	private final PersonalAnalysisService service;
	private final PersonalityActivation activation;

	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	@PostMapping("/survey-sessions/{sessionId}/analyses")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AnalysisRequestResponse request(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long sessionId) {
		AnalysisRequestResponse receipt = service.request(Long.parseLong(jwt.getSubject()), sessionId);
		// 이미 성공한 결과면 적용을 한 번 더 시도한다(작업기의 적용 재시도는 서버 메모리라 재시작하면 빠진다).
		// 요청 트랜잭션(회차 잠금)이 끝난 뒤 따로 해서, 회원→회차 순으로 잠그는 설문 완료와 잠금 순서가 엇갈리지 않게 한다.
		if (Personality.AnalysisStatus.SUCCEEDED.name().equals(receipt.status())) {
			activation.tryActivate(receipt.resultId());
		}
		return receipt;
	}

	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	@GetMapping("/analysis-results/{resultId}")
	public AnalysisResultResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long resultId) {
		return service.get(Long.parseLong(jwt.getSubject()), resultId);
	}
}
