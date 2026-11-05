package com.e106.tingle.keyring;

import java.util.UUID;

/**
 * 호출자의 회원·설정·기기 잠금과 트랜잭션에 참여한다. 구현은 설정 담당 서비스의 현재 버전을
 * 확정한 뒤 공식 기기 규격의 전체 바이트를 공급해야 한다. 잠금 안에서 외부 통신하지 않는다.
 */
public interface KeyringPayloadProvider {
	KeyringPayload prepare(long userId, long deviceId, UUID ownerGeneration);
}
