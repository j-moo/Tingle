package com.e106.tingle.withdrawal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

@Component
public class WithdrawalReceiptTokens {
	private final byte[] secret;

	public WithdrawalReceiptTokens(
		@Value("${app.withdrawal.receipt-secret:${WITHDRAWAL_RECEIPT_SECRET:}}") String secret) {
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
	}

	public String issue(UUID operation) {
		if (secret.length < 32) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(
				mac.doFinal(("withdrawal-receipt-v1:" + operation).getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Receipt HMAC unavailable", e);
		}
	}

	public void verify(UUID operation, String supplied) {
		String expected = issue(operation);
		if (supplied == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
			supplied.getBytes(StandardCharsets.US_ASCII))) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
	}
}
