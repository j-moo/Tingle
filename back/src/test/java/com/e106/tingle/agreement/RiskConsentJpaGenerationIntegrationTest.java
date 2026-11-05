package com.e106.tingle.agreement;

import org.springframework.test.context.ActiveProfiles;

/** Repeat consent and closure regressions against a database generated solely from current JPA mappings. */
@ActiveProfiles(value = "test", inheritProfiles = false)
class RiskConsentJpaGenerationIntegrationTest extends RiskConsentIntegrationTest {
}
