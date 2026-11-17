package com.e106.tingle.chat.deletion;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Inventory;

/**
 * 채팅 삭제는 백업 사본 확인과 묶지 않는다(11-17 팀 결정). 승인된 외부 사본 목록은 비어 있다고 보고 DB 에서 지운다.
 * DB 백업에서 복원하면 삭제 저널(FileDeletionJournal)로 지운 채팅을 다시 지운다(RestoreReplayService).
 */
@Configuration(proxyBeanMethods = false)
public class ChatDeletionConfiguration {
	static final String NO_EXTERNAL_COPY = "no-external-copy-v1";

	@Bean
	ChatDeletionReferenceSource noExternalChatCopies() {
		return context -> new Inventory(true, true, NO_EXTERNAL_COPY, List.of());
	}
}
