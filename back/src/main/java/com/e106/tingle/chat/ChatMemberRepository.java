package com.e106.tingle.chat;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ChatMemberRepository extends JpaRepository<ChatMember, Long> {
	Optional<ChatMember> findByChatRoomIdAndUserId(long roomId, long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from ChatMember m where m.chatRoomId=:roomId and m.userId=:userId")
	Optional<ChatMember> lockMember(long roomId, long userId);
}
