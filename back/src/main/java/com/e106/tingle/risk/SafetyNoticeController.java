package com.e106.tingle.risk;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.risk.dto.SafetyNoticeDisplayed;
import com.e106.tingle.risk.dto.SafetyNotices;

import lombok.RequiredArgsConstructor;

/** 채팅방의 내 AI 위험 안내. 조회 → 앱이 화면에 보여 줌 → 사용자가 확인(안내 닫기)하면 표시 확인(ACK). */
@RestController
@RequestMapping("/api/chat-rooms/{id}/safety-notices")
@RequiredArgsConstructor
public class SafetyNoticeController {

	private final SafetyNoticeService service;

	@GetMapping
	public ResponseEntity<SafetyNotices> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.list(id, Long.parseLong(jwt.getSubject())));
	}

	@PutMapping("/{noticeId}/displayed")
	public ResponseEntity<SafetyNoticeDisplayed> displayed(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
		@PathVariable long noticeId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.displayed(id, noticeId, Long.parseLong(jwt.getSubject())));
	}
}
