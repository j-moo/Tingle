package com.e106.tingle.user;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.chat.ChatRoom;
import com.e106.tingle.chat.ChatRoomRepository;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.ProposalService;

import tools.jackson.databind.JsonNode;

/**
 * 채팅 상대 프로필 조회(GET /api/chat-rooms/{chatRoomId}/partner-profile) 권한과 응답 모양.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class ChatPartnerProfileIntegrationTest extends IntegrationTestSupport {
	@Autowired
	private ChatRoomRepository rooms;
	@Autowired
	private ProposalService proposals;
	@Autowired
	private ProposalRepository proposalRows;
	@Autowired
	private MatchingParticipantRepository participantRows;
	private JsonNode first;
	private JsonNode second;
	private String firstNickname;
	private String secondNickname;
	private long firstId;
	private long secondId;
	private ChatRoom room;

	@BeforeEach
	void prepareChat() throws Exception {
		first = login("google", newSocialId());
		second = login("google", newSocialId());
		firstNickname = completeOnboarding(first);
		secondNickname = completeOnboarding(second);
		firstId = userIdOf(first);
		secondId = userIdOf(second);
		// 채팅방은 두 사람이 제안을 모두 수락했을 때만 생긴다(채팅 테스트와 같은 방식).
		MatchProposal proposal = proposalRows.saveAndFlush(MatchProposal.proposed(firstId, secondId, clock.instant()));
		participantRows.saveAllAndFlush(List.of(new MatchingParticipant(proposal.getId(), firstId),
			new MatchingParticipant(proposal.getId(), secondId)));
		for (long userId : List.of(firstId, secondId)) {
			proposals.decide(proposal.getId(), userId, MatchProposal.Decision.ACCEPT, UUID.randomUUID(),
				proposals.profile(proposal.getId(), userId).profileVersion());
		}
		room = rooms.findBySourceProposalId(proposal.getId()).orElseThrow();
	}

	@Test
	void participantSeesPartnersCurrentProfileWithoutPrivateFields() throws Exception {
		// 선택 항목이 비어 있는 상대: 키는 그대로 두고 null, 사진 없음
		partnerProfile(first).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.nickname").value(secondNickname))
			.andExpect(jsonPath("$.age").value(ProfileRules.age(LocalDate.parse("2001-03-15"),
				LocalDate.now(clock))))
			.andExpect(jsonPath("$.gender").value("FEMALE"))
			.andExpect(jsonPath("$.smokingStatus").value("NON_SMOKER"))
			.andExpect(jsonPath("$.drinkingFrequency").value("MONTHLY_TWO_TO_FOUR"))
			.andExpect(jsonPath("$.religion").value(nullValue()))
			.andExpect(jsonPath("$.introduction").value(nullValue()))
			.andExpect(jsonPath("$.hasProfilePhoto").value(false))
			.andExpect(jsonPath("$.birthDate").doesNotExist())
			.andExpect(jsonPath("$.userId").doesNotExist())
			.andExpect(jsonPath("$.imageUrl").doesNotExist());

		// 매번 최신 값을 읽는다
		jdbc.update("update profile set religion='UNDISCLOSED', introduction='산책 좋아해요', "
			+ "photo_url='profile/' || user_id || '/00000000-0000-4000-8000-000000000001.jpg' "
			+ "where user_id=?", secondId);
		partnerProfile(first).andExpect(status().isOk())
			.andExpect(jsonPath("$.religion").value("UNDISCLOSED"))
			.andExpect(jsonPath("$.introduction").value("산책 좋아해요"))
			.andExpect(jsonPath("$.hasProfilePhoto").value(true));
	}

	@Test
	void listShowsOnlyTheReadableRoomsCurrentPartnerNickname() throws Exception {
		// 양쪽 목록에는 각각 상대 닉네임만 보인다. 닉네임 변경은 다음 조회에 반영된다.
		var list = get("/api/chat-rooms");
		perform(list, first, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].partnerNickname").value(secondNickname))
			.andExpect(jsonPath("$.items[0].userId").doesNotExist());
		perform(get("/api/chat-rooms"), second, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].partnerNickname").value(firstNickname));
		String updated = "새닉" + secondNickname;
		jdbc.update("update profile set nickname=? where user_id=?", updated, secondId);
		perform(get("/api/chat-rooms"), first, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].partnerNickname").value(updated));

		JsonNode outsider = login("google", newSocialId());
		completeOnboarding(outsider);
		perform(get("/api/chat-rooms"), outsider, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty());
		jdbc.update("update chat set closed_at=? where id=?", Timestamp.from(clock.instant()), room.getId());
		perform(get("/api/chat-rooms"), first, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty());
		perform(get("/api/chat-rooms/" + room.getPublicId()), first, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.partnerNickname").doesNotHaveJsonPath());
	}

	@Test
	void nonParticipantsAndMissingRoomsGetTheSame404AndLeftMembersGet410() throws Exception {
		JsonNode outsider = login("google", newSocialId());
		completeOnboarding(outsider);
		partnerProfile(outsider).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		perform(get("/api/chat-rooms/" + Long.MAX_VALUE + "/partner-profile"), first, null)
			.andExpect(status().isNotFound());
		// 내가 나간 방은 채팅 API와 같이 410
		jdbc.update("update chat_participant set left_at=? where chat_id=? and user_id=?",
			Timestamp.from(clock.instant()), room.getId(), firstId);
		partnerProfile(first).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("CHAT_CLOSED"));
		perform(get("/api/chat-rooms/0/partner-profile"), second, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void endedOrBlockedRoomsAreGone() throws Exception {
		// 차단은 방이 아직 닫히지 않았어도 채팅 API와 같이 바로 막힌다
		jdbc.update("update matching_participant set blocked_at=? where matching_id=? and user_id=?",
			Timestamp.from(clock.instant()), room.getPairId(), secondId);
		partnerProfile(first).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("CHAT_CLOSED"));
		jdbc.update("update matching_participant set blocked_at=null where matching_id=?", room.getPairId());
		partnerProfile(first).andExpect(status().isOk());

		jdbc.update("update chat set closed_at=? where id=?", Timestamp.from(clock.instant()), room.getId());
		partnerProfile(second).andExpect(status().isGone());

		// closed_at 이 비어 있어도 생성 후 168시간이 지났으면 끝난 방
		Instant created = clock.instant().minus(Duration.ofHours(168)).minusSeconds(1);
		jdbc.update("update chat set closed_at=null, created_at=?, expires_at=? where id=?",
			Timestamp.from(created), Timestamp.from(created.plus(Duration.ofHours(168))), room.getId());
		partnerProfile(second).andExpect(status().isGone());
	}

	private ResultActions partnerProfile(JsonNode tokens) throws Exception {
		return perform(get("/api/chat-rooms/" + room.getPublicId() + "/partner-profile"), tokens, null);
	}
}
