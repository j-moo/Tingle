package com.e106.tingle.chat;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.chat.dto.ChatPage;
import com.e106.tingle.chat.dto.ChatRoomView;
import com.e106.tingle.chat.dto.MessagePage;
import com.e106.tingle.chat.dto.MessageView;
import com.e106.tingle.chat.dto.ReadMessagesRequest;
import com.e106.tingle.chat.dto.ReadMessagesResult;
import com.e106.tingle.chat.dto.SendMessageRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/chat-rooms")
@RequiredArgsConstructor
public class ChatController {
	private final ChatService service;

	@GetMapping
	public ResponseEntity<ChatPage> list(@AuthenticationPrincipal Jwt jwt,
		@RequestParam(required = false) @Size(max = 256) String cursor,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
		return ok(service.list(Long.parseLong(jwt.getSubject()), cursor, limit));
	}

	@GetMapping("/{id}")
	public ResponseEntity<ChatRoomView> room(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
		return ok(service.room(id, Long.parseLong(jwt.getSubject())));
	}

	@GetMapping("/{id}/messages")
	public ResponseEntity<MessagePage> messages(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
		@RequestParam(required = false) @Size(max = 256) String cursor,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
		@RequestParam(required = false) @Min(0) Long afterSequence) {
		return ok(service.messages(id, Long.parseLong(jwt.getSubject()), cursor, limit, afterSequence));
	}

	@PostMapping("/{id}/messages")
	public ResponseEntity<MessageView> send(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
		@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody SendMessageRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(
			service.send(id, Long.parseLong(jwt.getSubject()), request.clientMessageId(), key, request.text()));
	}

	@PostMapping("/{id}/reads")
	public ResponseEntity<ReadMessagesResult> read(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
		@Valid @RequestBody ReadMessagesRequest request) {
		return ok(service.read(id, Long.parseLong(jwt.getSubject()), request.messageIds()));
	}

	private <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
