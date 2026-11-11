package com.e106.tingle.keyring;

import java.util.Arrays;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.dto.KeyringView;
import com.e106.tingle.keyring.dto.Keyrings;
import com.e106.tingle.keyring.dto.PurchaseInfo;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class KeyringReadService {

	private static final int MAX_LABEL_LENGTH = 10;

	private final KeyringReadRepository keyrings;
	private final KeyringProperties properties;

	@Transactional(readOnly = true)
	public Keyrings getKeyrings(long userId) {
		return new Keyrings(keyrings.findOwned(userId));
	}

	@Transactional(readOnly = true)
	public PurchaseInfo getPurchaseInfo(long userId) {
		return new PurchaseInfo(properties.purchaseUrl(), !keyrings.hasActiveBinding(userId));
	}

	/**
	 * 별명은 앞뒤 공백을 지우고 코드 포인트 10자까지. 비우면 일련번호 앞 10자로 보인다.
	 * 제어 문자와 폭 없는 공백·방향 제어 같은 서식 문자는 거절하고, 줄바꿈 없는 공백·한글 채움 문자도 공백으로 본다.
	 */
	@Transactional
	public KeyringView updateLabel(long userId, long deviceId, String requested) {
		String label = normalizeLabel(requested);
		if (!keyrings.updateLabel(userId, deviceId, label)) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		return keyrings.findOwned(userId).stream().filter(view -> view.deviceId() == deviceId).findFirst()
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
	}

	private static String normalizeLabel(String requested) {
		if (requested == null) {
			return null;
		}
		int[] points = requested.codePoints().toArray();
		for (int point : points) {
			if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST);
			}
		}
		int start = 0;
		int end = points.length;
		while (start < end && isBlank(points[start])) {
			start++;
		}
		while (end > start && isBlank(points[end - 1])) {
			end--;
		}
		// 결합 부호·변형 선택자·점자 빈칸처럼 혼자서는 보이지 않는 문자만 남으면 공백만 보낸 것과 같다.
		if (Arrays.stream(points, start, end).allMatch(KeyringReadService::isInvisible)) {
			return null;
		}
		if (end - start > MAX_LABEL_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return new String(points, start, end - start);
	}

	private static boolean isInvisible(int point) {
		int type = Character.getType(point);
		return isBlank(point) || type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
			|| point == 0x2800;
	}

	/** 화면에 빈칸으로만 보이는 문자. 한글 채움 문자(U+115F·U+1160·U+3164·U+FFA0)도 포함한다. */
	private static boolean isBlank(int point) {
		return Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0x115F || point == 0x1160
			|| point == 0x3164 || point == 0xFFA0;
	}
}
