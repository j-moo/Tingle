package com.e106.tingle.user;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.e106.tingle.chat.ChatService;
import com.e106.tingle.proposal.ProposalService;
import com.e106.tingle.user.dto.ProfileResponse;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * 프로필 사진 올리기·삭제·조회(기능 명세 PRO-02). 사진 파일 키는 응답에 넣지 않고, 사진은 권한을 확인한 뒤 바이트로만 내려준다.
 * 경로의 {userId}가 로그인한 회원인지는 UserPathInterceptor 가 먼저 확인한다.
 */
@RestController
@RequiredArgsConstructor
public class ProfilePhotoController {

	private final ProfilePhotoService photoService;
	private final ProposalService proposalService;
	private final ChatService chatService;

	// multipart 파일 필드 file 하나. 앱이 HEIC 를 JPEG 으로 바꿔 올린다.
	@PostMapping(path = "/api/users/{userId}/profile/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ProfileResponse upload(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file) {
		return photoService.upload(userId(jwt), file);
	}

	@DeleteMapping("/api/users/{userId}/profile/photo")
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt) {
		photoService.delete(userId(jwt));
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	// 본인 사진. 프로필 응답에는 사진 유무(hasProfilePhoto)만 있어 화면 표시는 이 경로로 받는다.
	@GetMapping("/api/users/{userId}/profile/photo")
	@RequiresOnboarding(OnboardingStatus.KEYRING_REQUIRED)
	public ResponseEntity<byte[]> own(@AuthenticationPrincipal Jwt jwt) {
		return jpeg(photoService.photoOf(userId(jwt)));
	}

	// 유효한 제안의 당사자만(아니면 404, 끝난 제안은 410 PROPOSAL_EXPIRED). 판단은 ProposalService 와 같다.
	@GetMapping("/api/match-proposals/{proposalId}/partner-photo")
	public ResponseEntity<byte[]> proposalPartner(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long proposalId) {
		return jpeg(photoService.photoOf(proposalService.partnerOf(proposalId, userId(jwt))));
	}

	// 이용 중인 채팅방의 참여자만(아니면 404, 나간·끝난 방은 410 CHAT_CLOSED). 판단은 ChatService 와 같다.
	@GetMapping("/api/chat-rooms/{chatRoomId}/partner-photo")
	public ResponseEntity<byte[]> chatPartner(@AuthenticationPrincipal Jwt jwt,
		@PathVariable @Positive long chatRoomId) {
		return jpeg(photoService.photoOf(chatService.partnerOf(chatRoomId, userId(jwt))));
	}

	private static ResponseEntity<byte[]> jpeg(byte[] photo) {
		return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).body(photo);
	}

	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}
}
