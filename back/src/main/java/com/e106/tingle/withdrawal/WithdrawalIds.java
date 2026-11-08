package com.e106.tingle.withdrawal;

import java.util.UUID;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

public final class WithdrawalIds {
	private WithdrawalIds() {
	}

	public static UUID parse(String value) {
		try {
			UUID result = UUID.fromString(value);
			if (result.toString().equalsIgnoreCase(value)) {
				return result;
			}
		} catch (IllegalArgumentException | NullPointerException ignored) {
			// Malformed IDs never reach SQL or appear in an error message.
		}
		throw new BusinessException(ErrorCode.INVALID_REQUEST);
	}
}
