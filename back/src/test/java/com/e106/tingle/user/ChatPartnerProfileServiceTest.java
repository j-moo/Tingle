package com.e106.tingle.user;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.e106.tingle.chat.ChatMember;
import com.e106.tingle.chat.ChatMemberRepository;
import com.e106.tingle.chat.ChatRoom;
import com.e106.tingle.chat.ChatRoomRepository;
import com.e106.tingle.chat.ChatService;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

class ChatPartnerProfileServiceTest {

	@Test
	void withdrawnPartnersEmptyRowReadFirstStillEndsAsClosedRoom() {
		// 탈퇴한 상대의 참여자 행은 user_id 가 비어 있다. 그 행이 내 행보다 먼저 읽혀도 오류 없이 끝난 방(410)이어야 한다.
		ChatRoomRepository rooms = mock(ChatRoomRepository.class);
		ChatMemberRepository members = mock(ChatMemberRepository.class);
		ChatRoom room = mock(ChatRoom.class);
		ChatMember withdrawn = mock(ChatMember.class);
		ChatMember me = mock(ChatMember.class);
		when(rooms.findByPublicId(7L)).thenReturn(Optional.of(room));
		when(room.getId()).thenReturn(70L);
		when(members.findByChatRoomId(70L)).thenReturn(List.of(withdrawn, me));
		when(withdrawn.getUserId()).thenReturn(null);
		when(me.getUserId()).thenReturn(1L);
		ChatPartnerProfileService service = new ChatPartnerProfileService(rooms, members, mock(ChatService.class),
			mock(ProfileRepository.class), Clock.systemUTC());

		assertThatThrownBy(() -> service.get(7L, 1L)).isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException)e).getErrorCode()).isEqualTo(ErrorCode.CHAT_CLOSED);
	}
}
