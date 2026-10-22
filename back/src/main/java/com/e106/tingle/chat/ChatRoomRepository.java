package com.e106.tingle.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
	Optional<ChatRoom> findByPublicId(UUID publicId);

	Optional<ChatRoom> findBySourceProposalId(long proposalId);

	Optional<ChatRoom> findFirstByPairIdAndClosedAtIsNullAndExpiresAtAfter(long pairId, Instant now);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from ChatRoom r where r.publicId = :publicId")
	Optional<ChatRoom> lockByPublicId(@Param("publicId") UUID publicId);

	@Query("select r from ChatRoom r join ChatMember m on m.chatRoomId = r.id "
		+ "where m.userId = :userId and m.leftAt is null and r.closedAt is null and r.expiresAt > :now "
		+ "and r.id < :before order by r.id desc")
	List<ChatRoom> page(@Param("userId") long userId, @Param("now") Instant now,
		@Param("before") long before, Pageable page);
}
