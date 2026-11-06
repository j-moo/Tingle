import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;

import com.e106.tingle.keyring.proof.KeyringProofTranscript;
import com.e106.tingle.keyring.proof.KeyringProofVerifier;

/** Independent profile-storage transcript check; BE profile issuance is a separate change. */
public final class ProfileInterop {
	private static boolean verify(PublicKey key, byte[] bytes, String signature) throws Exception {
		Signature verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(key);
		verifier.update(bytes);
		return verifier.verify(Base64.getUrlDecoder().decode(signature));
	}

	public static void main(String[] args) throws Exception {
		Properties data = new Properties();
		try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
			data.load(input);
		}
		byte[] payload = Base64.getUrlDecoder().decode(data.getProperty("payload"));
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
		if (payload.length != 196 || !hash.equals(data.getProperty("hash"))) {
			throw new AssertionError("Stored bytes differ from the signed payload");
		}
		byte[] spki = new byte[44];
		byte[] prefix = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
		System.arraycopy(prefix, 0, spki, 0, prefix.length);
		System.arraycopy(Base64.getUrlDecoder().decode(data.getProperty("publicKey")), 0, spki, 12, 32);
		PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
		byte[] serverBytes = Base64.getUrlDecoder().decode(data.getProperty("serverPublicKey"));
		System.arraycopy(serverBytes, 0, spki, 12, 32);
		PublicKey serverKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
		String serverId = "dev-ed25519-" + HexFormat.of().formatHex(serverBytes, 0, 8);
		if (!serverId.equals(data.getProperty("serverKeyId"))) {
			throw new AssertionError("Server key identifier mismatch");
		}
		String nonce = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
		UUID owner = UUID.fromString("33333333-3333-4333-8333-333333333333");
		UUID run = UUID.fromString("22222222-2222-4222-8222-222222222222");
		String transcript = String.join("\n", "TINGLE-KEYRING-SERVER-SETTINGS", "tingle-dev-settings-proof-v1",
			serverId, "synthetic-device", "test-key-01", "9007199254740993",
			owner.toString(), run.toString(), "10", "tingle-profile-storage-v1", hash, nonce,
			"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE", "1793577900") + "\n";
		if (!verify(serverKey, transcript.getBytes(StandardCharsets.UTF_8), data.getProperty("serverSignature"))
			|| verify(key, transcript.getBytes(StandardCharsets.UTF_8), data.getProperty("serverSignature"))
			|| verify(serverKey, transcript.substring(0, transcript.length() - 1).getBytes(StandardCharsets.UTF_8),
				data.getProperty("serverSignature"))
			|| verify(serverKey, transcript.replace("profile-storage", "other-storage").getBytes(StandardCharsets.UTF_8),
				data.getProperty("serverSignature"))) {
			throw new AssertionError("Profile server transcript or final LF mismatch");
		}
		KeyringProofTranscript stored = KeyringProofTranscript.stored("test-key-01", "synthetic-device",
			9007199254740993L, owner, run, nonce, 10, hash);
		if (!new KeyringProofVerifier().verify(KeyringProofTranscript.PROTOCOL_VERSION, stored,
			data.getProperty("stored"), key)) {
			throw new AssertionError("Backend STORED verifier rejects the profile payload hash");
		}
		System.out.println("PASS: independent JDK profile transcript and actual BE STORED verifier; tamper/LF rejected");
	}
}
