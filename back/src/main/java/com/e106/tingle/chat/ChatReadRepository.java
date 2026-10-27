package com.e106.tingle.chat;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatReadRepository extends JpaRepository<ChatRead, Long> {
	List<ChatRead> findByUserIdAndMessageIdIn(long userId, Collection<Long> messageIds);
}
