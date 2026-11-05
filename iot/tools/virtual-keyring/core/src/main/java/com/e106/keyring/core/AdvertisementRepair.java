package com.e106.keyring.core;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Public XOR erasure equations over encrypted chunks. Authentication remains the codec's responsibility. */
final class AdvertisementRepair {
	static final int FIRST_MARKER = 0x30;
	static final int LAST_MARKER = 0x36;

	private AdvertisementRepair() {
	}

	static BigInteger coefficients(byte[] frame) {
		int transfer = ((frame[1] & 255) << 16) | ((frame[2] & 255) << 8) | (frame[3] & 255);
		int count = frame[5] & 255;
		byte[] seed = ByteBuffer.allocate(6).putInt(transfer).put(frame[4]).put(frame[5]).array();
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed);
			BigInteger bits = new BigInteger(1, digest).and(BigInteger.ONE.shiftLeft(count).subtract(BigInteger.ONE));
			return bits.signum() == 0 ? bits.setBit((frame[4] & 255) % count) : bits;
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("REPAIR_HASH_UNAVAILABLE", exception);
		}
	}

	static byte[] frame(List<byte[]> originals, int seed) {
		byte[] first = originals.get(0);
		byte[] last = originals.get(originals.size() - 1);
		byte[] repair = new byte[13];
		repair[0] = (byte)(FIRST_MARKER + last.length - 7);
		System.arraycopy(first, 1, repair, 1, 3);
		repair[4] = (byte)seed;
		repair[5] = first[5];
		BigInteger bits = coefficients(repair);
		for (int index = 0; index < originals.size(); index++) {
			if (bits.testBit(index)) {
				byte[] part = originals.get(index);
				for (int offset = 6; offset < part.length; offset++) {
					repair[offset] ^= part[offset];
				}
			}
		}
		return repair;
	}
}
