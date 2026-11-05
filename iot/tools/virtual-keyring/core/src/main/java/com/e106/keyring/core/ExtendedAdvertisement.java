package com.e106.keyring.core;

import java.util.Arrays;

/** One authenticated message per service-data element; lengths include the 128-bit service UUID. */
public final class ExtendedAdvertisement {
	public static final byte MARKER = 0x40;
	public static final int SERVICE_OVERHEAD = 18;
	public static final int MAX_ADVERTISEMENT_BYTES = 255;
	// Android limits updates of an enabled advertising set to 251 bytes.
	public static final int MAX_UPDATE_BYTES = 251;
	public static final int MAX_ENCRYPTED_BYTES = MAX_UPDATE_BYTES - SERVICE_OVERHEAD - 1;
	public static final int REQUIRED_BYTES = 216;

	private ExtendedAdvertisement() {
	}

	public static boolean supported(boolean extendedSupported, int maximumLength) {
		return extendedSupported && maximumLength >= REQUIRED_BYTES;
	}

	public static byte[] wrap(byte[] encrypted, int maximumLength) {
		if (encrypted == null || encrypted.length < 28 || encrypted.length > MAX_ENCRYPTED_BYTES
			|| encrypted.length + SERVICE_OVERHEAD + 1 > maximumLength) {
			throw new IllegalArgumentException("EXTENDED_MESSAGE_TOO_LARGE");
		}
		byte[] frame = new byte[encrypted.length + 1];
		frame[0] = MARKER;
		System.arraycopy(encrypted, 0, frame, 1, encrypted.length);
		return frame;
	}

	public static byte[] unwrap(byte[] frame) {
		if (frame == null || frame.length < 29 || frame.length > MAX_ENCRYPTED_BYTES + 1
			|| frame[0] != MARKER) {
			throw new IllegalArgumentException("INVALID_EXTENDED_MESSAGE");
		}
		return Arrays.copyOfRange(frame, 1, frame.length);
	}
}
