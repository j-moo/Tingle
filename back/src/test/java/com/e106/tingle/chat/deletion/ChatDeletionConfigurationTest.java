package com.e106.tingle.chat.deletion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatDeletionConfigurationTest {

	@Test
	void deletionDoesNotWaitForBackupCopyEvidence() {
		// 백업 사본 확인 없이 지운다(11-17 팀 결정). 외부 사본 목록은 비어 있다고 확정한 승인 목록이다.
		var inventory = new ChatDeletionConfiguration().noExternalChatCopies().inspect(null);
		assertThat(inventory.authorizesDeletion()).isTrue();
		assertThat(inventory.references()).isEmpty();
	}
}
