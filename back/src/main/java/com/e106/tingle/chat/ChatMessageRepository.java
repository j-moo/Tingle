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
