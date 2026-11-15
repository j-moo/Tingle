package com.e106.tingle.chat;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
	@Query(value = "select exists (select 1 from chat_message m join chat_participant p on p.chat_id=m.chat_id "
		+ "where m.chat_id=:roomId and p.user_id=:userId and m.sender_participant_id<>p.id "
		+ "and (p.read_state -> cast(m.id as text) ->> 'readAt') is null)", nativeQuery = true)
	boolean hasUnacknowledgedPartnerMessage(@Param("roomId") long roomId, @Param("userId") long userId);

	// 권한을 통과한 페이지의 공개 닉네임 · 새 메시지 여부 · 마지막 메시지를 한 번의 조회로 읽는다.
	// 마지막 메시지는 방의 last_message_seq 한 건(유니크 (chat_id, seq))이고, 본문은 저장할 때 주소 가리기를 마친 값이다.
	@Query(value = "select p.chat_id as \"roomId\", profile.nickname as \"partnerNickname\", "
		+ "r.last_message_seq as \"lastMessageSeq\", left(last.body, " + LAST_MESSAGE_PREVIEW + ") as \"lastMessage\", "
		+ "cast(floor(extract(epoch from last.created_at) * 1000) as bigint) as \"lastMessageAtMillis\", "
		+ "exists (select 1 from chat_message m where m.chat_id=p.chat_id "
		+ "and m.sender_participant_id<>p.id and (p.read_state->cast(m.id as text)->>'readAt') is null) "
		+ "as \"hasNewMessage\" from chat_participant p join chat r on r.id=p.chat_id "
		+ "join matching pair on pair.id=r.matching_id "
		+ "left join profile on profile.user_id=case when pair.user_low_id=p.user_id "
		+ "then pair.user_high_id when pair.user_high_id=p.user_id then pair.user_low_id end "
		+ "and profile.birth_date is not null "
		+ "left join chat_message last on last.chat_id=r.id and last.seq=r.last_message_seq "
		+ "where p.user_id=:userId and p.chat_id in :roomIds",
		nativeQuery = true)
	List<ListSummary> summarizeList(@Param("roomIds") Collection<Long> roomIds,
		@Param("userId") long userId);

	interface ListSummary {
		Long getRoomId();

		String getPartnerNickname();

		boolean getHasNewMessage();

		// 본문과 같은 조회 시점의 순번(방 엔티티를 먼저 읽은 뒤 새 메시지가 와도 어긋나지 않게)
		Long getLastMessageSeq();

		String getLastMessage();

		Long getLastMessageAtMillis();
	}

	/** 목록 한 줄에 보여 줄 마지막 메시지 길이 */
	int LAST_MESSAGE_PREVIEW = 100;

	@Query("select m from ChatMessage m join ChatMember p on p.id=m.senderParticipantId "
		+ "where p.userId=:userId and m.requestKey=:key")
	Optional<ChatMessage> findBySenderIdAndRequestKey(long userId, UUID key);

	@Query("select m from ChatMessage m join ChatMember p on p.id=m.senderParticipantId "
		+ "where m.chatRoomId=:roomId and p.userId=:userId and m.clientMessageId=:clientId")
	Optional<ChatMessage> findByChatRoomIdAndSenderIdAndClientMessageId(long roomId, long userId, UUID clientId);

	@Query("select m from ChatMessage m where m.chatRoomId=:roomId and m.id in :ids")
	List<ChatMessage> findByChatRoomIdAndPublicIdIn(long roomId, Collection<Long> ids);

	@Query("select m from ChatMessage m where m.chatRoomId = :roomId and m.sequence < :before "
		+ "order by m.sequence desc")
	List<ChatMessage> page(@Param("roomId") long roomId, @Param("before") long before, Pageable page);

	@Query("select m from ChatMessage m where m.chatRoomId=:roomId and m.sequence>:after "
		+ "order by m.sequence asc")
	List<ChatMessage> after(@Param("roomId") long roomId, @Param("after") long after, Pageable page);

	List<ChatMessage> findByChatRoomIdOrderBySequenceAsc(long roomId);
}
