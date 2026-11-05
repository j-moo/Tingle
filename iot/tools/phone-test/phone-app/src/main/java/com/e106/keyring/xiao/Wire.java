package com.e106.keyring.xiao;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.CRC32;

/** Shared binary codec. Test payloads contain synthetic bytes, never real profile text. */
public final class Wire {
	public static final UUID SERVICE = UUID.fromString("8e8c0001-67a0-4d2f-9c17-120b4c80e106");
	public static final UUID RX = UUID.fromString("8e8c0002-67a0-4d2f-9c17-120b4c80e106");
	public static final UUID TX = UUID.fromString("8e8c0003-67a0-4d2f-9c17-120b4c80e106");
	public static final int MAX_FRAME = 2500;
	public static final int NOT_FOUND = 7;
	private static final SecureRandom RANDOM = new SecureRandom();

	private Wire() {
	}

	public static ByteBuffer little(byte[] bytes) {
		return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
	}

	public static byte[] randomId() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return bytes;
	}

	public static long crc(byte[] bytes) {
		CRC32 crc = new CRC32();
		crc.update(bytes);
		return crc.getValue();
	}

	public static byte[] frame(int op, int request, byte[] body) {
		if (body.length > MAX_FRAME - 14) {
			throw new IllegalArgumentException("Frame too large");
		}
		ByteBuffer buffer = ByteBuffer.allocate(body.length + 14).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put((byte)'K').put((byte)'P').put((byte)1).put((byte)op);
		buffer.putInt(request).putShort((short)body.length).put(body);
		buffer.putInt((int)crc(Arrays.copyOf(buffer.array(), buffer.position())));
		return buffer.array();
	}

	public static byte[] response(byte[] frame, int op, int request) {
		if (frame.length < 15 || frame.length > MAX_FRAME) {
			throw new IllegalArgumentException("Response length");
		}
		ByteBuffer buffer = little(frame);
		if (buffer.get() != 'K' || buffer.get() != 'P' || buffer.get() != 1 || (buffer.get() & 255) != (op | 128)
			|| buffer.getInt() != request || (buffer.getShort() & 65535) + 14 != frame.length
			|| Integer.toUnsignedLong(buffer.getInt(frame.length - 4)) != crc(Arrays.copyOf(frame, frame.length - 4))) {
			throw new IllegalArgumentException("Response identity/CRC");
		}
		return Arrays.copyOfRange(frame, 10, frame.length - 4);
	}

	public static byte[] sync(byte[] token, long utc, int revision, int policy, int rule,
		int opportunitySeconds, byte[] snapshot, byte[] data) {
		if (token.length != 16 || snapshot.length != 16 || data.length == 0 || data.length > 1024
			|| utc <= 0 || revision <= 0 || policy <= 0 || rule <= 0 || opportunitySeconds <= 0) {
			throw new IllegalArgumentException("Invalid profile");
		}
		ByteBuffer buffer = ByteBuffer.allocate(58 + data.length).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put(token).putLong(utc).putInt(revision).putInt(policy).putInt(rule).putInt(opportunitySeconds);
		buffer.put(snapshot).putShort((short)data.length).put(data);
		return buffer.array();
	}

	public static byte[] concat(byte[] first, byte[] second) {
		byte[] out = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, out, first.length, second.length);
		return out;
	}

	public static String hex(byte[] bytes) {
		StringBuilder out = new StringBuilder();
		for (byte value : bytes) {
			out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
		}
		return out.toString();
	}

	public static byte[] unhex(String text) {
		if ((text.length() & 1) != 0) {
			throw new IllegalArgumentException("Invalid hex");
		}
		byte[] result = new byte[text.length() / 2];
		for (int i = 0; i < result.length; i++) {
			result[i] = (byte)Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
		}
		return result;
	}
}
