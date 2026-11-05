package com.e106.tingle.safety;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 현재 유효한 본인 소유 차단. 과거 제안·채팅 종료 증거와 생명주기를 분리한다. */
@Entity
@Table(name = "user_block", uniqueConstraints = @UniqueConstraint(name = "uq_user_block_owner_target",
	columnNames = {"owner_id", "target_id"}), indexes = @Index(name = "ix_user_block_owner_created",
	columnList = "owner_id,created_at,id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBlock {
	@Id
	private UUID id;
	@Column(nullable = false)
	private long ownerId;
	@Column(nullable = false)
	private long targetId;
	@Column(nullable = false)
	private long matchingId;
	@Column(nullable = false)
	private Instant createdAt;
}
