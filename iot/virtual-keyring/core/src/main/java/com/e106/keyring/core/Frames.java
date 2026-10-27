package com.e106.keyring.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Always uses 20-byte GATT values, so ATT MTU 23 is enough. One ordered transfer at a time. */
public final class Frames {
	private static final int HEADER_SIZE = 7;
	private static final int BODY_SIZE = 13;
	private final ByteArrayOutputStream assembled = new ByteArrayOutputStream();
	private int transferId = -1;
	private int expected;
	private int count;
	private long startedAt;

	public static List<byte[]> split(byte[] packet, int transferId) {
		if (packet.length == 0 || packet.length > Packet.MAX_BYTES) {
			throw new IllegalArgumentException("INVALID_MESSAGE_SIZE");
		}
		int count = (packet.length + BODY_SIZE - 1) / BODY_SIZE;
		List<byte[]> frames = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			int offset = index * BODY_SIZE;
			int length = Math.min(BODY_SIZE, packet.length - offset);
			ByteBuffer frame = ByteBuffer.allocate(HEADER_SIZE + length);
			frame.put((byte)1).putShort((short)transferId).putShort((short)index).putShort((short)count);
			frame.put(packet, offset, length);
			frames.add(frame.array());
		}
		return frames;
	}

	public byte[] accept(byte[] value, long now) {
		if (value == null || value.length <= HEADER_SIZE || value.length > 20) {
			return reject();
		}
		ByteBuffer frame = ByteBuffer.wrap(value);
		int version = frame.get() & 0xff;
		int id = frame.getShort() & 0xffff;
		int index = frame.getShort() & 0xffff;
		int total = frame.getShort() & 0xffff;
		if (version != 1 || total == 0 || total > 40 || index >= total
			|| (index < total - 1 && value.length != 20)) {
			return reject();
		}
		if (index == 0 && transferId == -1) {
			transferId = id;
			count = total;
			startedAt = now;
		}
		if (id != transferId || index != expected || total != count || now - startedAt > 15000) {
			return reject();
		}
		assembled.write(value, HEADER_SIZE, value.length - HEADER_SIZE);
		if (assembled.size() > Packet.MAX_BYTES) {
			return reject();
		}
		expected++;
		if (expected == count) {
			byte[] packet = assembled.toByteArray();
			reset();
			return packet;
		}
		return null;
	}

	public void reset() {
		assembled.reset();
		transferId = -1;
		expected = 0;
		count = 0;
	}

	private byte[] reject() {
		reset();
		throw new IllegalArgumentException("INVALID_FRAME_SEQUENCE");
	}
}
