package com.e106.tingle.chat.realtime;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.stereotype.Service;

import com.e106.tingle.chat.ChatService;
import com.e106.tingle.chat.deletion.restore.RestoreServingGateFilter;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.OnboardingStatus;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatSocketAccess {
	private final JwtDecoder decoder;
	private final OnboardingService onboarding;
	private final ChatService chats;
	private final RestoreServingGateFilter restoreGate;

	public long require(String token, long roomId, long afterSequence) {
		long userId = require(token, roomId);
		chats.requireRecoveryPosition(roomId, userId, afterSequence);
		return userId;
	}

	public long require(String token, long roomId) {
		if (!restoreGate.servingAllowed()) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		Jwt jwt;
		try {
			jwt = decoder.decode(token);
		} catch (JwtValidationException exception) {
			boolean revoked = exception.getErrors().stream().anyMatch(e -> e.getErrorCode().equals("session_revoked"));
			boolean expired = exception.getErrors().stream().anyMatch(e -> e.getErrorCode().equals("token_expired"));
			throw new BusinessException(revoked ? ErrorCode.SESSION_REVOKED
				: expired ? ErrorCode.ACCESS_TOKEN_EXPIRED : ErrorCode.ACCESS_TOKEN_INVALID);
		} catch (JwtException exception) {
			throw new BusinessException(ErrorCode.ACCESS_TOKEN_INVALID);
		}
		long userId = Long.parseLong(jwt.getSubject());
		if (!onboarding.canAccess(userId, OnboardingStatus.READY)) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
		if (!chats.room(roomId, userId).canRead()) {
			throw new BusinessException(ErrorCode.CHAT_CLOSED);
		}
		return userId;
	}
}
