import com.e106.tingle.keyring.proof.KeyringProofTranscript;
import com.e106.tingle.keyring.proof.KeyringProofVerifier;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;

/** Verifies firmware C output with the actual backend transcript and JDK Ed25519. */
public final class ProofInterop {
	private static boolean verify(PublicKey key, byte[] text, String signature) throws Exception {
		Signature verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(key);
		verifier.update(text);
		return verifier.verify(Base64.getUrlDecoder().decode(signature));
	}

	public static void main(String[] args) throws Exception {
		Properties vectors = new Properties();
		try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
			vectors.load(input);
		}
		byte[] spki = new byte[44];
		byte[] prefix = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
		System.arraycopy(prefix, 0, spki, 0, prefix.length);
		System.arraycopy(Base64.getUrlDecoder().decode(vectors.getProperty("publicKey")), 0, spki, 12, 32);
		PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
		String nonce = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
		UUID challenge = UUID.fromString("11111111-1111-4111-8111-111111111111");
		KeyringProofTranscript registrationText = KeyringProofTranscript.registration("test-key-01", "synthetic-device",
			challenge, nonce, 1793577900L);
		byte[] registration = registrationText.bytes();
		KeyringProofVerifier backendVerifier = new KeyringProofVerifier();
		if (!backendVerifier.verify(KeyringProofTranscript.PROTOCOL_VERSION, registrationText,
			vectors.getProperty("registration"), key)) {
			throw new AssertionError("Registration transcript mismatch");
		}
		UUID owner = UUID.fromString("33333333-3333-4333-8333-333333333333");
		UUID run = UUID.fromString("22222222-2222-4222-8222-222222222222");
		KeyringProofTranscript storedText = KeyringProofTranscript.stored("test-key-01", "synthetic-device", 9007199254740993L,
			owner, run, nonce, Long.MAX_VALUE, vectors.getProperty("hash"));
		byte[] stored = storedText.bytes();
		if (!backendVerifier.verify(KeyringProofTranscript.PROTOCOL_VERSION, storedText,
			vectors.getProperty("stored"), key)) {
			throw new AssertionError("STORED transcript mismatch / int64 rounding");
		}
		if (backendVerifier.verify("synthetic-v1", storedText, vectors.getProperty("stored"), key)
			|| backendVerifier.verify(KeyringProofTranscript.PROTOCOL_VERSION, storedText,
				vectors.getProperty("stored") + "=", key)) {
			throw new AssertionError("Wrong protocol or noncanonical signature accepted");
		}
		stored[stored.length - 2] ^= 1;
		if (verify(key, stored, vectors.getProperty("stored"))) {
			throw new AssertionError("Tampered transcript accepted");
		}
		byte[] shortened = java.util.Arrays.copyOf(registration, registration.length - 1);
		if (verify(key, shortened, vectors.getProperty("registration"))) {
			throw new AssertionError("Missing final LF accepted");
		}
		System.out.println("PASS: actual Java backend verifies registration/STORED; tamper and missing LF rejected");
	}
}
