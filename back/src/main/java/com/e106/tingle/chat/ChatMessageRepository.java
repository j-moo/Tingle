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

	List<ChatMessage> findByChatRoomIdOrderBySequenceAsc(long roomId);
}
