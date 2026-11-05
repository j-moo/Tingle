package com.e106.tingle.chat.deletion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ChatDeletionConfiguration {
	@Bean
	@ConditionalOnMissingBean(ChatDeletionReferenceSource.class)
	@ConditionalOnProperty(name = "app.backup-evidence.enabled", havingValue = "false", matchIfMissing = true)
	ChatDeletionReferenceSource unresolvedChatDeletionReferences() {
		return context -> ChatDeletionReferenceSource.Inventory.unresolved();
	}
}
