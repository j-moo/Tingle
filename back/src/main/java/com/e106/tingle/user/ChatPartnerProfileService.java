package com.e106.tingle.user;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.chat.ChatMember;
import com.e106.tingle.chat.ChatMemberRepository;
import com.e106.tingle.chat.ChatRoom;
import com.e106.tingle.chat.ChatRoomRepository;
import com.e106.tingle.chat.ChatService;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.dto.PartnerProfileResponse;

import lombok.RequiredArgsConstructor;

/**
 * 채팅 상대의 현재 공개 프로필(기능 명세 PRO-03). 방 번호는 짐작할 수 있어 참여자인지 먼저 확인한다.
 */
@Service
@RequiredArgsConstructor
public class ChatPartnerProfileService {

	private final ChatRoomRepository chatRooms;
	private final ChatMemberRepository chatMembers;
	private final ChatService chatService;
	private final ProfileRepository profileRepository;
	private final Clock clock;

	/**
	 * 방이 없거나 참여자가 아니면 같은 404. 내가 나간 방·끝난 방(closed_at·168시간 경과)은 410(채팅 API와 같은 기준).
	 * 채팅에서 이미 막힌 방(차단·탈퇴·AI 동의 철회 등)도 채팅 API와 같은 기준(ChatService.available)으로 410 이다.
	 * 상대 프로필은 매번 새로 읽는다(최신 값).
	 * 탈퇴한 상대의 참여자 행은 user_id 가 비어 있다. 종교는 미입력도 비공개와 같게 보낸다(PRO: 둘 다 '종교 정보 비공개').
	 */
	@Transactional(readOnly = true)
	public PartnerProfileResponse get(long chatRoomId, long userId) {
		ChatRoom room = chatRooms.findByPublicId(chatRoomId).orElseThrow(ChatPartnerProfileService::notFound);
		List<ChatMember> members = chatMembers.findByChatRoomId(room.getId());
		ChatMember me = members.stream().filter(m -> Objects.equals(m.getUserId(), userId)).findFirst()
			.orElseThrow(ChatPartnerProfileService::notFound);
		if (me.getLeftAt() != null || !room.active(clock.instant()) || !chatService.available(room, userId)) {
			throw new BusinessException(ErrorCode.CHAT_CLOSED);
		}
		Profile profile = members.stream().map(ChatMember::getUserId).filter(id -> id != null && id != userId)
			.findFirst().flatMap(profileRepository::findById).filter(p -> p.getBirthDate() != null)
			.orElseThrow(ChatPartnerProfileService::notFound);
		return new PartnerProfileResponse(profile.getNickname(),
			ProfileRules.age(profile.getBirthDate(), LocalDate.now(clock)), profile.getGender(),
			profile.getSmokingStatus(), profile.getDrinkingFrequency(),
			profile.getReligion() == null ? Religion.UNDISCLOSED : profile.getReligion(),
			profile.getIntroduction(), profile.getImageUrl() != null);
	}

	private static BusinessException notFound() {
		return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
	}
}
