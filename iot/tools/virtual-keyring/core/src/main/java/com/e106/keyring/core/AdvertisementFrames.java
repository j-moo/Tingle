package com.e106.keyring.core;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 18 bytes of service-data overhead + at most 13 bytes = legacy advertisement's 31 bytes. */
public final class AdvertisementFrames {
	public static final byte MARKER = 0x22;
	// Keep slow partial transfers long enough to finish, within the authenticated envelope's 180-second limit.
	public static final long RECOVERY_WINDOW_MS = 150000;
	private static final int CHUNK = 7;
	private final Map<String, Assembly> pending = new LinkedHashMap<>();
	private final Map<String, Long> completed = new LinkedHashMap<>();

	public static List<byte[]> split(byte[] data, int transfer) {
		if (data.length == 0 || data.length > 596) {
			throw new IllegalArgumentException("INVALID_ADVERTISEMENT_MESSAGE_SIZE");
		}
		List<byte[]> frames = new ArrayList<>();
		int count = (data.length + CHUNK - 1) / CHUNK;
		for (int index = 0; index < count; index++) {
			int length = Math.min(CHUNK, data.length - index * CHUNK);
			byte[] frame = new byte[6 + length];
			frame[0] = MARKER;
			frame[1] = (byte)(transfer >> 16);
			frame[2] = (byte)(transfer >> 8);
			frame[3] = (byte)transfer;
			frame[4] = (byte)index;
			frame[5] = (byte)count;
			System.arraycopy(data, index * CHUNK, frame, 6, length);
			frames.add(frame);
		}
		return frames;
	}

	public byte[] accept(String radioSource, byte[] frame, long now) {
		expire(now);
		if (frame == null || frame.length < 7 || frame.length > 13) {
			return null;
		}
		int marker = frame[0] & 255;
		boolean repair = marker >= AdvertisementRepair.FIRST_MARKER && marker <= AdvertisementRepair.LAST_MARKER;
		if (marker != (MARKER & 255) && !repair) {
			return null;
		}
		int index = frame[4] & 255;
		int count = frame[5] & 255;
		if (count == 0 || count > 86 || (repair && frame.length != 13)
			|| (!repair && (index >= count || (index < count - 1 && frame.length != 13)))) {
			return null;
		}
		String key = radioSource + ":" + (frame[1] & 255) + ":" + (frame[2] & 255) + ":" + (frame[3] & 255);
		if (completed.containsKey(key)) {
			return null;
		}
		Assembly assembly = pending.get(key);
		if (assembly == null) {
			trim(pending, 32);
			assembly = new Assembly(count, now);
			pending.put(key, assembly);
		}
		if (assembly.chunks.length != count) {
			pending.remove(key);
			return null;
		}
		int lastBytes = repair ? marker - AdvertisementRepair.FIRST_MARKER + 1
			: index == count - 1 ? frame.length - 6 : 0;
		if (lastBytes > 0 && ((assembly.lastBytes > 0 && assembly.lastBytes != lastBytes)
			|| (count - 1) * CHUNK + lastBytes > 596)) {
			pending.remove(key);
			return null;
		}
		if (lastBytes > 0) {
			assembly.lastBytes = lastBytes;
		}
		byte[] chunk = Arrays.copyOfRange(frame, 6, frame.length);
		if (!repair) {
			if (assembly.chunks[index] != null && !Arrays.equals(assembly.chunks[index], chunk)) {
				pending.remove(key);
				return null;
			}
			assembly.chunks[index] = chunk;
		}
		BigInteger coefficients = repair ? AdvertisementRepair.coefficients(frame) : BigInteger.ONE.shiftLeft(index);
		if (!assembly.add(coefficients, Arrays.copyOf(chunk, CHUNK))) {
			pending.remove(key);
			return null;
		}
		if (assembly.rank != count || assembly.lastBytes == 0) {
			return null;
		}
		pending.remove(key);
		trim(completed, 256);
		completed.put(key, now);
		return assembly.recover();
	}

	public Progress progress(long now) {
		expire(now);
		int received = 0;
		int total = 0;
		for (Assembly assembly : pending.values()) {
			int count = assembly.rank;
			if (total == 0 || count * total > received * assembly.chunks.length) {
				received = count;
				total = assembly.chunks.length;
			}
		}
		return new Progress(received, total, pending.size());
	}

	private void expire(long now) {
		pending.entrySet().removeIf(entry -> now - entry.getValue().started > RECOVERY_WINDOW_MS);
		completed.entrySet().removeIf(entry -> now - entry.getValue() > 180000);
	}

	public static final class Progress {
		public final int received;
		public final int total;
		public final int pending;

		private Progress(int received, int total, int pending) {
			this.received = received;
			this.total = total;
			this.pending = pending;
		}
	}

	private static void trim(Map<?, ?> map, int limit) {
		if (map.size() >= limit) {
			map.remove(map.keySet().iterator().next());
		}
	}

	private static final class Assembly {
		private final byte[][] chunks;
		private final BigInteger[] equations;
		private final byte[][] values;
		private final long started;
		private int rank;
		private int lastBytes;

		private Assembly(int count, long started) {
			chunks = new byte[count][];
			equations = new BigInteger[count];
			values = new byte[count][];
			this.started = started;
		}

		private boolean add(BigInteger coefficients, byte[] data) {
			for (int index = 0; index < chunks.length; index++) {
				if (!coefficients.testBit(index)) {
					continue;
				}
				if (equations[index] == null) {
					equations[index] = coefficients;
					values[index] = data;
					rank++;
					return true;
				}
				coefficients = coefficients.xor(equations[index]);
				xor(data, values[index]);
			}
			for (byte value : data) {
				if (value != 0) {
					return false;
				}
			}
			return true;
		}

		private byte[] recover() {
			byte[][] solved = new byte[chunks.length][];
			for (int index = chunks.length - 1; index >= 0; index--) {
				solved[index] = values[index].clone();
				for (int next = index + 1; next < chunks.length; next++) {
					if (equations[index].testBit(next)) {
						xor(solved[index], solved[next]);
					}
				}
			}
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			for (int index = 0; index < solved.length; index++) {
				output.write(solved[index], 0, index == solved.length - 1 ? lastBytes : CHUNK);
			}
			return output.toByteArray();
		}

		private static void xor(byte[] target, byte[] source) {
			for (int index = 0; index < target.length; index++) {
				target[index] ^= source[index];
			}
		}
	}
}
