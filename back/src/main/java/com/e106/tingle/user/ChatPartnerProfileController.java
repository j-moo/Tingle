package com.e106.tingle.user;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.user.dto.PartnerProfileResponse;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * 채팅 상대 프로필 조회(기능 명세 PRO-03).
 * 가입 단계는 기본값(READY)을 쓴다. 가입을 한 번 마친 회원은 단계가 뒤로 가도 기존 채팅처럼 계속 볼 수 있다.
 */
@RestController
@RequiredArgsConstructor
public class ChatPartnerProfileController {

	private final ChatPartnerProfileService chatPartnerProfileService;

	// 상대 개인정보라 캐시하지 않는다.
	@GetMapping("/api/chat-rooms/{chatRoomId}/partner-profile")
	public ResponseEntity<PartnerProfileResponse> partnerProfile(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long chatRoomId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(chatPartnerProfileService.get(chatRoomId, Long.parseLong(jwt.getSubject())));
	}
}
