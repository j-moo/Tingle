package com.e106.tingle.chat;

import org.springframework.test.context.ActiveProfiles;

/** Repeat the real notice regressions against a database generated only by current JPA mappings. */
@ActiveProfiles(value = "test", inheritProfiles = false)
class ChatNoticeJpaGenerationIntegrationTest extends ChatNoticeIntegrationTest {
}
