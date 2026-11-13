package com.e106.tingle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 파트 간 공유 계약 데이터의 back 복사본(src/test/resources/contracts)을 읽는다.
 * 각 파트는 자기 폴더의 복사본만 읽고, 원본과의 일치는 같은 SHA-256 고정으로 보장한다.
 * 해시가 다르면 원본 파트의 파일과 이 복사본을 함께 갱신한다.
 */
public final class ContractFixtures {

	private ContractFixtures() {
	}

	/** ai-server/tests/fixtures/mvp-policy-20261026-cases.json 과 같은 bytes. */
	public static byte[] mvpPolicyCases() {
		return read("mvp-policy-20261026-cases.json",
			"9bedd16058295211224008b6553099300c72358fa85f478f980879e174b540a8");
	}

	/** infra/db/prod-changes/247-keyring-server-proofs.sql 과 같은 bytes. */
	public static String keyringServerProofsSql() {
		return new String(read("prod-changes/247-keyring-server-proofs.sql",
			"f2e5602d05f1bfa223aa4ce4c0dba1cd3085ff8a9eeeefa85918879fbd0c0f82"), StandardCharsets.UTF_8);
	}

	/** infra/db/prod-changes/2026-11-06-member-withdrawal.sql 과 같은 bytes. */
	public static String memberWithdrawalSql() {
		return new String(read("prod-changes/2026-11-06-member-withdrawal.sql",
			"d49764b185ce3bfc5521a30bae8c948ae7159d99f179ca02924eb33cb26769b5"), StandardCharsets.UTF_8);
	}

	public static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] read(String name, String expected) {
		try (InputStream in = ContractFixtures.class.getResourceAsStream("/contracts/" + name)) {
			if (in == null) {
				throw new IllegalStateException("contracts/" + name + " 복사본이 없다");
			}
			byte[] bytes = in.readAllBytes();
			String actual = sha256(bytes);
			if (!actual.equals(expected)) {
				throw new IllegalStateException(
					"contracts/" + name + " 계약 해시 불일치: " + actual + " (기대 " + expected + ")");
			}
			return bytes;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
