package com.e106.tingle.support.lost;

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

@Entity
@Table(name = "lost_case_command", uniqueConstraints = @UniqueConstraint(
	name = "uq_lost_case_command", columnNames = {"case_id", "request_key"}),
	indexes = @Index(name = "ix_lost_case_command_case", columnList = "case_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LostCaseCommand {
	@Id
	private UUID id;
	@Column(nullable = false)
	private Long caseId;
	@Column(nullable = false)
	private UUID requestKey;
	@Column(nullable = false, length = 64)
	private String requestHash;
	@Column(nullable = false)
	private Instant createdAt;
}
