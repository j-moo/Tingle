package com.e106.keyring.core;

/** Fixed face-up gravity reference, independent of the orientation at app launch. */
public final class KeyringMotion {
	public static final long PULSE_MS = 500;
	public static final long PULSE_INTERVAL_MS = 1000;
	public static final long FEEDBACK_MS = PULSE_INTERVAL_MS + PULSE_MS;

	private KeyringMotion() {
	}

	/** Returns inverse physical pitch/yaw for Android Camera's screen coordinate system. */
	public static float[] fromGravity(float gravityX, float gravityY, float gravityZ, int displayQuarterTurns) {
		float horizontal;
		float vertical;
		switch (displayQuarterTurns) {
			case 1:
				horizontal = gravityY;
				vertical = -gravityX;
				break;
			case 2:
				horizontal = -gravityX;
				vertical = -gravityY;
				break;
			case 3:
				horizontal = -gravityY;
				vertical = gravityX;
				break;
			default:
				horizontal = gravityX;
				vertical = gravityY;
		}
		float pitch = (float)-Math.toDegrees(Math.atan2(vertical, Math.hypot(horizontal, gravityZ)));
		float yaw = (float)Math.toDegrees(Math.atan2(horizontal, Math.hypot(vertical, gravityZ)));
		return new float[] {limit(pitch * 0.65f), limit(yaw * 0.65f)};
	}

	private static float limit(float angle) {
		return Math.max(-28, Math.min(28, angle));
	}

	public static boolean isPulse(long elapsedMillis) {
		return elapsedMillis >= 0 && (elapsedMillis < PULSE_MS
			|| (elapsedMillis >= PULSE_INTERVAL_MS && elapsedMillis < FEEDBACK_MS));
	}

	public static long[] vibrationPattern() {
		return new long[] {0, PULSE_MS, PULSE_INTERVAL_MS - PULSE_MS, PULSE_MS};
	}
}
