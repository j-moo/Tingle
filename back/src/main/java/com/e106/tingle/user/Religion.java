package com.e106.tingle.user;

/**
 * 종교. 미입력(null)과 공개하지 않음(UNDISCLOSED)은 구분해 저장하지만 상대에게는 둘 다 비공개로 보인다.
 */
public enum Religion {
	NONE,
	PROTESTANT,
	CATHOLIC,
	BUDDHIST,
	OTHER,
	UNDISCLOSED
}
