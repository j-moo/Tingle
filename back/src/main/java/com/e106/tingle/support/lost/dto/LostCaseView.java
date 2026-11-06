package com.e106.tingle.support.lost.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.e106.tingle.withdrawal.dto.WithdrawalView;

public record LostCaseView(long caseId, String status, long deviceId, UUID ownerGeneration,
	boolean reauthenticationRequired, boolean operatorVerified, boolean finalConfirmed, Long infoRequestId,
	Instant replyDueAt, Instant closedAt, String userMessage, List<History> history,
	String receiptToken, WithdrawalView withdrawal) {

	public record History(long historyId, String event, String message, Instant createdAt) {
	}
}
