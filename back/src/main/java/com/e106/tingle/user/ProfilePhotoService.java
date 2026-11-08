package com.e106.tingle.user;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.e106.tingle.agreement.OptionalConsent;
import com.e106.tingle.agreement.UserConsentRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.settings.snapshot.SettingsChangeCoordinator;
import com.e106.tingle.user.dto.ProfileResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * 프로필 사진 한 장을 서버 폴더(tingle.photo.dir)에 저장·교체·삭제하고 읽는다.
 * DB(profile.photo_url)에는 폴더 기준 키 profile/{userId}/{uuid}.jpg 만 저장한다(절대 경로·URL 아님).
 * 교체 순서: 새 파일 쓰기 → DB 변경 → 커밋되면 이전 파일 삭제(실패·롤백이면 새 파일 삭제, 기존 사진 유지).
 */
@Slf4j
@Service
public class ProfilePhotoService {

	// 원본 최대 10MB. spring.servlet.multipart.max-file-size 와 같은 값이다.
	static final long MAX_BYTES = 10L * 1024 * 1024;
	// DB CHECK(ck_profile_photo_url)와 같은 형식. 이 형식만 파일 경로로 바꿔 폴더 밖 접근(../)을 막는다.
	private static final Pattern KEY = Pattern.compile("profile/[0-9]+/[0-9a-f-]{36}\\.jpg");

	private final ProfileRepository profileRepository;
	private final UserRepository userRepository;
	private final SettingsChangeCoordinator changes;
	private final TransactionTemplate transaction;
	private final UserConsentRepository consents;
	private final Path root;

	public ProfilePhotoService(ProfileRepository profileRepository, UserRepository userRepository,
		SettingsChangeCoordinator changes, TransactionTemplate transaction, UserConsentRepository consents,
		@Value("${tingle.photo.dir}") Path root) {
		this.profileRepository = profileRepository;
		this.userRepository = userRepository;
		this.changes = changes;
		this.transaction = transaction;
		this.consents = consents;
		this.root = root.toAbsolutePath().normalize();
	}

	/**
	 * 사진을 규격(정사각형·최대 1080px·JPEG)으로 바꿔 저장하고 기존 사진을 교체한다.
	 * 이미지 변환은 DB 연결을 잡기 전에 끝낸다(트랜잭션 밖).
	 */
	public ProfileResponse upload(long userId, MultipartFile file) {
		byte[] jpeg = ProfilePhotoImages.toProfileJpeg(read(file));
		return transaction.execute(status -> {
			// 같은 회원의 업로드·삭제가 동시에 와도 이전 파일 정리가 꼬이지 않게 회원 행을 잠근다.
			userRepository.findByIdForUpdate(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
			// 회원 행을 잠근 뒤 확인해야 동시에 온 동의 철회와 엇갈려 사진이 남지 않는다.
			if (!consents.isAgreed(userId, OptionalConsent.PROFILE_PHOTO.name())) {
				throw new BusinessException(ErrorCode.OPTIONAL_CONSENT_REQUIRED);
			}
			changes.lockIfPresent(userId);
			Profile profile = profileRepository.findById(userId).orElseGet(() -> Profile.empty(userId));
			String previous = profile.getImageUrl();
			String key = write(userId, jpeg);
			deleteAfterCompletion(previous, key);
			profile.changeImageUrl(key);
			profileRepository.saveAndFlush(profile);
			changes.advanceAndCapture(userId);
			return ProfileResponse.from(profile);
		});
	}

	// 프로필 행이 없거나 사진이 이미 없어도 성공이다(반복 삭제 204).
	public void delete(long userId) {
		transaction.executeWithoutResult(status -> {
			userRepository.findByIdForUpdate(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
			changes.lockIfPresent(userId);
			profileRepository.findById(userId).filter(profile -> profile.getImageUrl() != null).ifPresent(profile -> {
				deleteAfterCompletion(profile.getImageUrl(), null);
				profile.changeImageUrl(null);
				changes.advanceAndCapture(userId);
			});
		});
	}

	// 프로필 행을 다른 곳에서 지울 때(만 19세 미만 탈퇴) 쓴다. 지금 트랜잭션이 커밋되면 사진 파일을 지운다.
	void deleteFileAfterCommit(String key) {
		if (key != null) {
			deleteAfterCompletion(key, null);
		}
	}

	/**
	 * 회원의 현재 사진(JPEG 바이트). 권한 확인은 호출하는 쪽이 먼저 한다. 사진이 없으면 410 PHOTO_REMOVED.
	 */
	public byte[] photoOf(long userId) {
		String key = profileRepository.findById(userId).map(Profile::getImageUrl).orElse(null);
		if (key == null) {
			throw new BusinessException(ErrorCode.PHOTO_REMOVED);
		}
		try {
			return Files.readAllBytes(resolve(key));
		} catch (NoSuchFileException e) {
			// 막 교체돼 이전 파일이 지워진 순간이거나 파일이 유실된 경우. 다시 요청하면 새 사진을 받는다.
			// 사진 키·경로는 사진 접근 정보라 로그에 남기지 않는다. 회원 번호로 DB 값을 찾아 대조한다.
			log.warn("DB에 있는 프로필 사진 파일이 없습니다 userId={}", userId);
			throw new BusinessException(ErrorCode.PHOTO_REMOVED);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static byte[] read(MultipartFile file) {
		if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
			throw new BusinessException(ErrorCode.PHOTO_INVALID);
		}
		try {
			return file.getBytes();
		} catch (IOException e) {
			throw new BusinessException(ErrorCode.PHOTO_INVALID);
		}
	}

	private String write(long userId, byte[] jpeg) {
		String key = "profile/" + userId + "/" + UUID.randomUUID() + ".jpg";
		Path target = resolve(key);
		try {
			Files.createDirectories(target.getParent());
			Files.write(target, jpeg, StandardOpenOption.CREATE_NEW);
		} catch (IOException e) {
			deleteFile(target);
			throw new UncheckedIOException(e);
		}
		return key;
	}

	// 커밋되면 이전 파일(onCommit)을, 롤백되면 이번에 쓴 파일(onRollback)을 지운다.
	private void deleteAfterCompletion(String onCommit, String onRollback) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				String key = status == STATUS_COMMITTED ? onCommit : onRollback;
				if (key != null) {
					deleteFile(resolve(key));
				}
			}
		});
	}

	// ponytail: 파일 삭제 실패는 기록만 한다(DB는 이미 반영). 남은 파일이 많아지면 키 목록과 대조하는 정리 작업을 둔다.
	private static void deleteFile(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			// 경로와 예외 메시지(경로 포함)는 남기지 않고 실패 종류만 남긴다.
			log.warn("프로필 사진 파일을 지우지 못했습니다 failure={}", e.getClass().getSimpleName());
		}
	}

	Path resolve(String key) {
		Path path = root.resolve(key).normalize();
		if (!KEY.matcher(key).matches() || !path.startsWith(root)) {
			throw new IllegalStateException("잘못된 프로필 사진 키입니다");
		}
		return path;
	}

	/** Durable withdrawal tasks call this after the account transaction has committed. */
	public void deleteWithdrawnFile(String key) throws IOException {
		Files.deleteIfExists(resolve(key));
	}
}
