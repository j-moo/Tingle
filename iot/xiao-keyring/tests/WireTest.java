import com.e106.keyring.xiao.Wire;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class WireTest {
	public static void main(String[] args) throws Exception {
		byte[] token = new byte[16];
		token[0] = 2;
		byte[] snapshot = new byte[16];
		for (int i = 0; i < 16; i++) {
			snapshot[i] = (byte)i;
		}
		byte[] data = new byte[1024];
		for (int i = 0; i < data.length; i++) {
			data[i] = (byte)i;
		}
		byte[] body = Wire.sync(token, 1700000000L, 7, 1, 1, 604800, snapshot, data);
		byte[] expected = Wire.unhex(Files.readString(Path.of(args[0])).trim());
		if (!Arrays.equals(Wire.frame(3, 42, body), expected)) {
			throw new AssertionError("C/Java binary sync fixture mismatch");
		}
		byte[] response = Wire.frame(131, 42, new byte[] {0, 7});
		if (!Arrays.equals(Wire.response(response, 3, 42), new byte[] {0, 7})) {
			throw new AssertionError("response round trip");
		}
		response[11] ^= 1;
		try {
			Wire.response(response, 3, 42);
			throw new AssertionError("corrupt response accepted");
		} catch (IllegalArgumentException expectedFailure) {
			// Expected: CRC corruption cannot complete a request.
		}
		System.out.println("PASS: Android codec matches shared 1024-byte C fixture and rejects corrupt receipts");
	}
}
