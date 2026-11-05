package com.e106.tingle.chat.notice;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.chat.dto.EntryView;
import com.e106.tingle.chat.dto.NoticeAck;
import com.e106.tingle.chat.dto.NoticesView;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/chat-rooms/{id}")
@RequiredArgsConstructor
public class ChatNoticeController {
	private final ChatNoticeService service;

	@PostMapping("/entries")
	public ResponseEntity<EntryView> enter(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
		@RequestHeader("Idempotency-Key") UUID key) {
		return ok(service.enter(id, Long.parseLong(jwt.getSubject()), key));
	}

	@GetMapping("/notices")
	public ResponseEntity<NoticesView> notices(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
		return ok(service.list(id, Long.parseLong(jwt.getSubject())));
	}

	@PutMapping("/notices/{noticeId}/ack")
	public ResponseEntity<NoticeAck> ack(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
		@PathVariable UUID noticeId, @RequestHeader("Idempotency-Key") UUID key) {
		return ok(service.ack(id, noticeId, Long.parseLong(jwt.getSubject()), key));
	}

	private <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
