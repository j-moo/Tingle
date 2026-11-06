import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

/** Independent JDK check of the proposed reset and epoch-bound v2 wire bytes. */
public final class ResetInterop {
	private static PublicKey publicKey(String raw) throws Exception {
		byte[] spki = new byte[44];
		byte[] prefix = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
		System.arraycopy(prefix, 0, spki, 0, 12);
		System.arraycopy(Base64.getUrlDecoder().decode(raw), 0, spki, 12, 32);
		return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
	}

	private static boolean verify(PublicKey key, byte[] text, String signature) throws Exception {
		Signature verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(key);
		verifier.update(text);
		return verifier.verify(Base64.getUrlDecoder().decode(signature));
	}

	private static void check(PublicKey key, String signature, String... lines) throws Exception {
		String transcript = String.join("\n", lines) + "\n";
		byte[] bytes = transcript.getBytes(StandardCharsets.UTF_8);
		if (!verify(key, bytes, signature)) {
			throw new AssertionError("Transcript mismatch: " + lines[0]);
		}
		if (verify(key, Arrays.copyOf(bytes, bytes.length - 1), signature)
			|| verify(key, transcript.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8), signature)) {
			throw new AssertionError("Noncanonical newline accepted");
		}
		bytes[bytes.length - 2] ^= 1;
		if (verify(key, bytes, signature)) {
			throw new AssertionError("Tampered transcript accepted");
		}
	}

	public static void main(String[] args) throws Exception {
		Properties v = new Properties();
		try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
			v.load(input);
		}
		PublicKey key = publicKey(v.getProperty("publicKey"));
		String owner = "11111111-1111-4111-8111-111111111111";
		String nextOwner = "44444444-4444-4444-8444-444444444444";
		String run = "22222222-2222-4222-8222-222222222222";
		String nonce = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
		check(publicKey(v.getProperty("serverPublicKey")), v.getProperty("authorization"),
			"TINGLE-KEYRING-RESET-AUTH", "tingle-keyring-reset-v1", "synthetic-server",
			"synthetic-key", "synthetic-device", "4", owner, run, "0", "1",
			v.getProperty("authChallengeId"), v.getProperty("authDeviceNonce"), nonce,
			"OWNER_DATA_V1", "REQUIRE_SYNCED");
		check(key, v.getProperty("cleared"), "TINGLE-KEYRING-RESET-CLEARED", "tingle-keyring-reset-v1",
			"synthetic-key", "synthetic-device", "4", owner, run, "0", "1", nonce,
			"OWNER_DATA_V1", "REQUIRE_SYNCED", "LOGICAL", "CLEARED");
		check(key, v.getProperty("challenge"), "TINGLE-KEYRING-RESET-CHALLENGE", "tingle-keyring-reset-v1",
			"synthetic-key", "synthetic-device", "1", nextOwner, v.getProperty("challengeId"),
			v.getProperty("deviceNonce"), "60");
		check(key, v.getProperty("registration"), "TINGLE-KEYRING-REGISTER", "tingle-device-proof-v2",
			"synthetic-key", "synthetic-device", run, nonce, "1793577900", "1", "UNOWNED");
		check(key, v.getProperty("stored"), "TINGLE-KEYRING-STORED", "tingle-device-proof-v2",
			"synthetic-key", "synthetic-device", "4", nextOwner, run, nonce, "1", v.getProperty("hash"),
			"STORED", "1");
		System.out.println("PASS: independent JDK reset AUTH/CHALLENGE/CLEARED and registration/STORED v2; LF/tamper checks");
	}
}
