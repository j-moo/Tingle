package com.e106.tingle;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가입 완료(READY)가 필요한 보호 API 대역. @RequiresOnboarding 이 없으면 READY가 필요하다.
 */
@TestConfiguration
@RestController
public class TestProtectedController {

	@GetMapping("/api/v1/test/protected")
	String protectedEndpoint() {
		return "ok";
	}

	// /api/auth 아래라고 해서 가입 검사에서 빠지지 않는지 확인용
	@GetMapping("/api/auth/test-protected")
	String protectedAuthEndpoint() {
		return "ok";
	}
}
