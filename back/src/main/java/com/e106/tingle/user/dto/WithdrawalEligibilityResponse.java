package com.e106.tingle.user.dto;

import java.time.Instant;
import java.util.List;

/** A point-in-time check; execution must check the same conditions again under the member lock. */
public record WithdrawalEligibilityResponse(boolean eligible, long registeredKeyringCount,
	long pendingResetCount, List<String> blockingCodes, Instant checkedAt) {
}
