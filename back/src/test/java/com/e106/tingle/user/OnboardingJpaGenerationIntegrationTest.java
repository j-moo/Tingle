package com.e106.tingle.user;

import org.springframework.test.context.ActiveProfiles;

/** SQL 선행 생성 없이 실제 JPA가 생성한 새 저장 모델에서 같은 HTTP 회귀를 실행한다. */
@ActiveProfiles(value = {"test"}, inheritProfiles = false)
class OnboardingJpaGenerationIntegrationTest extends OnboardingIntegrationTest {
}
