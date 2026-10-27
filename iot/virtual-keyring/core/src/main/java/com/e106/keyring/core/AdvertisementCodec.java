package com.e106.keyring.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** LAB-2 group membership, confidentiality and binding to a recipient's current radio session. */
public final class AdvertisementCodec {
	public static final UUID SERVICE = UUID.fromString("f8fd2a22-6039-4854-8897-70fcba711206");
	public static final UUID BROADCAST = new UUID(0, 0);
	public static final long MAX_AGE_MS = 180000;
	private static final byte[] CONTEXT = "E106 virtual-keyring LAB-2".getBytes(StandardCharsets.UTF_8);
	private static final SecureRandom RANDOM = new SecureRandom();
	private final SecretKeySpec key;

	public AdvertisementCodec(String group) {
		String normalized = normalize(group);
		byte[] bytes = new byte[16];
		for (int index = 0; index < bytes.length; index++) {
			bytes[index] = (byte)Integer.parseInt(normalized.substring(index * 2, index * 2 + 2), 16);
		}
		key = new SecretKeySpec(bytes, "AES");
	}

	public static String normalize(String value) {
		String code = value == null ? "" : value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
		if (!code.matches("[0-9A-F]{32}")) {
			throw new IllegalArgumentException("그룹 코드는 32자리입니다. 다른 기기의 코드를 그대로 입력해주세요.");
		}
		return code;
	}

	public static String newGroup() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		StringBuilder value = new StringBuilder();
		for (byte item : bytes) {
			value.append(String.format(Locale.ROOT, "%02X", item & 255));
		}
		return value.toString();
	}

	public static long newSession() {
		long value;
		do {
			value = RANDOM.nextLong();
		} while (value == 0);
		return value;
	}

	public byte[] seal(UUID source, long sourceSession, UUID target, long targetSession, Packet packet, long now) {
		byte[] body = packet.encode();
		ByteBuffer plain = ByteBuffer.allocate(56 + body.length);
		putId(plain, source);
		plain.putLong(sourceSession);
		putId(plain, target);
		plain.putLong(targetSession).putLong(now).put(body);
		byte[] nonce = new byte[12];
		RANDOM.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
			cipher.updateAAD(CONTEXT);
			byte[] encrypted = cipher.doFinal(plain.array());
			return ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array();
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("LAB_ENCRYPTION_UNAVAILABLE", exception);
		}
	}

	public Envelope open(byte[] encrypted, long now) {
		if (encrypted == null || encrypted.length < 86 || encrypted.length > 596) {
			throw new IllegalArgumentException("INVALID_ENCRYPTED_SIZE");
		}
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(encrypted, 12)));
			cipher.updateAAD(CONTEXT);
			ByteBuffer plain = ByteBuffer.wrap(cipher.doFinal(encrypted, 12, encrypted.length - 12));
			UUID source = getId(plain);
			long sourceSession = plain.getLong();
			UUID target = getId(plain);
			long targetSession = plain.getLong();
			long timestamp = plain.getLong();
			if (source.equals(BROADCAST) || sourceSession == 0) {
				throw new IllegalArgumentException("INVALID_ENVELOPE");
			}
			byte[] data = new byte[plain.remaining()];
			plain.get(data);
			Packet packet = Packet.decode(data);
			if (target.equals(BROADCAST) && ((packet.type != Packet.HELLO
				&& packet.type != Packet.PROFILE_BROADCAST) || targetSession != 0)) {
				throw new IllegalArgumentException("ONLY_HELLO_MAY_BROADCAST");
			}
			if (packet.type == Packet.HELLO && !packet.peer.deviceId.equals(source)) {
				throw new IllegalArgumentException("SENDER_ID_CONFLICT");
			}
			if (packet.type == Packet.PROFILE_BROADCAST && (!packet.profile.keyring.deviceId.equals(source)
				|| !target.equals(BROADCAST))) {
				throw new IllegalArgumentException("PROFILE_SENDER_CONFLICT");
			}
			if (timestamp < now - MAX_AGE_MS || timestamp > now + 30000) {
				// Classify time only after authentication and format checks; never trust foreign timestamps.
				throw new ClockException(timestamp > now);
			}
			return new Envelope(source, sourceSession, target, targetSession, timestamp, packet, messageId(encrypted));
		} catch (GeneralSecurityException exception) {
			throw new IllegalArgumentException("GROUP_AUTHENTICATION_FAILED", exception);
		}
	}

	public static final class ClockException extends IllegalArgumentException {
		private static final long serialVersionUID = 1L;
		public final boolean future;

		private ClockException(boolean future) {
			super(future ? "FUTURE_ENVELOPE" : "EXPIRED_ENVELOPE");
			this.future = future;
		}
	}

	/** Public nonce identifies one encrypted message, including all its repeated advertising fragments. */
	public static String messageId(byte[] encrypted) {
		return Base64.getEncoder().encodeToString(Arrays.copyOf(encrypted, 12));
	}

	private static void putId(ByteBuffer buffer, UUID id) {
		buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
	}

	private static UUID getId(ByteBuffer buffer) {
		return new UUID(buffer.getLong(), buffer.getLong());
	}

	public static final class Envelope {
		public final UUID source;
		public final long sourceSession;
		public final UUID target;
		public final long targetSession;
		public final long sentAt;
		public final Packet packet;
		public final String messageId;

		private Envelope(UUID source, long sourceSession, UUID target, long targetSession, long sentAt,
			Packet packet, String messageId) {
			this.source = source;
			this.sourceSession = sourceSession;
			this.target = target;
			this.targetSession = targetSession;
			this.sentAt = sentAt;
			this.packet = packet;
			this.messageId = messageId;
		}

		public boolean forSession(UUID id, long session) {
			return !source.equals(id) && (target.equals(BROADCAST)
				|| (target.equals(id) && targetSession == session));
		}
	}
}
