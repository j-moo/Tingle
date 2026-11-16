package com.e106.tingle.chat.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MessagePushPreviewTest {

	@Test
	void previewKeepsShortTextAndCutsLongTextWithoutSplittingEmoji() {
		assertThat(MessagePushState.preview("  안녕하세요  ")).isEqualTo("안녕하세요");
		assertThat(MessagePushState.preview(" ")).isNull();
		assertThat(MessagePushState.preview(null)).isNull();
		String hundred = "가".repeat(MessagePushState.PREVIEW_LENGTH);
		assertThat(MessagePushState.preview(hundred)).isEqualTo(hundred);
		assertThat(MessagePushState.preview(hundred + "나")).isEqualTo(hundred + "…");
		// 이모지(서로게이트 쌍)는 글자 하나로 세고 반으로 자르지 않는다
		String emoji = "😀".repeat(MessagePushState.PREVIEW_LENGTH + 1);
		assertThat(MessagePushState.preview(emoji)).isEqualTo("😀".repeat(MessagePushState.PREVIEW_LENGTH) + "…");
	}
}
