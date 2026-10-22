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
	Optional<ChatMessage> findBySenderIdAndRequestKey(long userId, UUID key);

	Optional<ChatMessage> findByChatRoomIdAndSenderIdAndClientMessageId(long roomId, long userId, UUID clientId);

	List<ChatMessage> findByChatRoomIdAndPublicIdIn(long roomId, Collection<UUID> ids);

	@Query("select m from ChatMessage m where m.chatRoomId = :roomId and m.sequence < :before "
		+ "order by m.sequence desc")
	List<ChatMessage> page(@Param("roomId") long roomId, @Param("before") long before, Pageable page);
}
