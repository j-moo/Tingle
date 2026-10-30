package com.e106.tingle.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
	@Query("select r from ChatRoom r where r.id=:publicId")
	Optional<ChatRoom> findByPublicId(long publicId);

	@Query("select r from ChatRoom r where r.matchingId=:proposalId")
	Optional<ChatRoom> findBySourceProposalId(long proposalId);

	@Query("select r from ChatRoom r where r.matchingId=:pairId and r.closedAt is null and r.expiresAt>:now")
	Optional<ChatRoom> findFirstByPairIdAndClosedAtIsNullAndExpiresAtAfter(long pairId, Instant now);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from ChatRoom r where r.id = :publicId")
	Optional<ChatRoom> lockByPublicId(@Param("publicId") long publicId);

	@Query("select r from ChatRoom r join ChatMember m on m.chatRoomId = r.id "
		+ "where m.userId = :userId and m.leftAt is null and r.closedAt is null and r.expiresAt > :now "
		+ "and (:firstPage = true or r.createdAt < :beforeCreatedAt "
		+ "or (r.createdAt = :beforeCreatedAt and r.id < :beforeId)) "
		+ "order by r.createdAt desc, r.id desc")
	List<ChatRoom> page(@Param("userId") long userId, @Param("now") Instant now,
		@Param("firstPage") boolean firstPage, @Param("beforeCreatedAt") Instant beforeCreatedAt,
		@Param("beforeId") long beforeId, Pageable page);
}
