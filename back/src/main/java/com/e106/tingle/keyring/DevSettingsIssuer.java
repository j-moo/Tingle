package com.e106.tingle.keyring;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.proof.ServerSettingsSigner;
import com.e106.tingle.keyring.proof.ServerSettingsTranscript;

import lombok.RequiredArgsConstructor;

/** 개발 v1은 발견 스위치만 전달한다. 성향 payload나 매칭 계산 완료를 뜻하지 않는다. */
@RequiredArgsConstructor
public final class DevSettingsIssuer {
	public static final String FORMAT = "tingle-dev-settings-v1";

	private final JdbcTemplate jdbc;
	private final ServerSettingsSigner signer;

	public KeyringPayload prepare(long userId, long deviceId, UUID generation) {
		return jdbc.queryForObject("select s.settings_version,p.matching_enabled from user_setting s "
			+ "join matching_preference p on p.user_id=s.user_id where s.user_id=?", (row, index) -> {
				Boolean enabled = row.getObject("matching_enabled", Boolean.class);
				if (enabled == null) {
					throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
				}
				return new KeyringPayload(userId, deviceId, generation, row.getLong("settings_version"),
					FORMAT, new byte[] {(byte)(enabled ? 1 : 0)});
			}, userId);
	}

	public DevSettingsProof sign(KeyringSyncRun run, String serial, String deviceNonce, Instant now) {
		Instant time = now.truncatedTo(ChronoUnit.SECONDS);
		var transcript = ServerSettingsTranscript.create(signer.keyId(), serial, run.getCredentialKeyId(),
			run.getKeyringId(), run.getOwnerGeneration(), run.getSyncRunId(), run.getSettingsVersion(),
			run.getPayloadFormat(), run.getPayload(), run.getNonce(), deviceNonce, time, run.getOwnershipEpoch());
		return new DevSettingsProof(run.getOwnershipEpoch() == null ? ServerSettingsTranscript.PROTOCOL_VERSION
			: ServerSettingsTranscript.PROTOCOL_VERSION_2, signer.keyId(), deviceNonce,
			time, signer.sign(transcript));
	}

	public static void requireDeviceNonce(String nonce) {
		try {
			if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{43}")) {
				throw new IllegalArgumentException("Invalid challenge");
			}
			byte[] decoded = Base64.getUrlDecoder().decode(nonce);
			String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
			if (decoded.length != 32 || !canonical.equals(nonce)) {
				throw new IllegalArgumentException("Noncanonical challenge");
			}
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
	}
}
