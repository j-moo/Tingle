package com.e106.tingle.chat.deletion.restore.ops;

import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.e106.tingle.chat.deletion.restore.FileDeletionJournal;
import com.e106.tingle.chat.deletion.restore.RestoreServingGateFilter;

import tools.jackson.databind.json.JsonMapper;

/** Explicit operator entry point. This class never starts Spring, HTTP, JPA or scheduled work. */
public final class RestoreOperationsCommand {
	private static final Set<String> OPERATIONS = Set.of("journal-init", "journal-status", "verify", "gate-status");
	private static final Set<String> VERIFY_OPTIONS = Set.of("namespace", "restore-generation",
		"expected-fingerprint", "fence-confirmed", "wait-seconds", "poll-seconds");
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private RestoreOperationsCommand() {
	}

	public static int execute(String[] args, Map<String, String> environment, PrintStream output) {
		String operation = args.length > 0 && OPERATIONS.contains(args[0]) ? args[0] : "unknown";
		try {
			if (args.length == 1 && "--help".equals(args[0])) {
				write(output, "help", "OK", "RESTORE_OPERATIONS", null, null);
				return 0;
			}
			Map<String, String> options = options(args, operation);
			String namespace = required(options, "namespace");
			if (!Set.of("tingle", "tingle_dev").contains(namespace)) {
				throw new IllegalArgumentException();
			}
			Path directory = configuredPath(environment, "TINGLE_CHAT_DELETION_JOURNAL_DIRECTORY");
			VerificationOptions verification = Set.of("verify", "gate-status").contains(operation)
				? verificationOptions(options, environment, directory, namespace) : null;
			FileDeletionJournal journal = new FileDeletionJournal(directory.toString(), MAPPER);
			if ("journal-init".equals(operation)) {
				FileDeletionJournal.initialize(directory);
				Path namespaceFile = directory.resolve("namespace");
				try (var channel = FileChannel.open(namespaceFile, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
					ByteBuffer bytes = StandardCharsets.US_ASCII.encode(namespace + "\n");
					while (bytes.hasRemaining()) {
						channel.write(bytes);
					}
					channel.force(true);
				}
				ownerOnly(directory, true);
				for (String name : Set.of("namespace", "journal.lock", "head.sha256", "intents.log")) {
					ownerOnly(directory.resolve(name), false);
				}
			}
			Path namespaceFile = directory.resolve("namespace");
			checkedPath(namespaceFile);
			if (!Files.isRegularFile(namespaceFile, LinkOption.NOFOLLOW_LINKS) || Files.size(namespaceFile) > 32
				|| !Files.readString(namespaceFile).equals(namespace + "\n")) {
				throw new IllegalStateException();
			}
			if ("gate-status".equals(operation)) {
				Path generationFile = configuredPath(environment, "APP_BACKUP_EVIDENCE_GENERATION_FILE");
				boolean allowed = Files.isRegularFile(generationFile, LinkOption.NOFOLLOW_LINKS)
					&& Files.size(generationFile) <= 200
					&& verification.generation().equals(Files.readString(generationFile).strip())
					&& verification.fingerprint().equals(journal.snapshot().fingerprint())
					&& new RestoreServingGateFilter(verification.generation(), verification.marker().toString(),
						MAPPER, journal).servingAllowed();
				write(output, operation, allowed ? "ALLOWED" : "BLOCKED",
					allowed ? null : "RESTORE_VERIFICATION_REQUIRED", null, null);
				return allowed ? 0 : 3;
			}
			if ("verify".equals(operation)) {
				boolean verified = RestoreOperationsVerifier.verify(verification, environment, journal, MAPPER);
				write(output, operation, verified ? "VERIFIED" : "PENDING",
					verified ? null : "RESTORE_VERIFICATION_PENDING", null, null);
				return verified ? 0 : 3;
			}
			var snapshot = journal.snapshot();
			write(output, operation, "OK", null, snapshot.fingerprint(), snapshot.entries().size());
			return 0;
		} catch (IllegalArgumentException exception) {
			write(output, operation, "ERROR", "INVALID_RESTORE_OPTIONS", null, null);
			return 2;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			write(output, operation, "ERROR", "RESTORE_OPERATION_INTERRUPTED", null, null);
			return 3;
		} catch (Exception exception) {
			// Never print exception messages, JDBC URLs, credentials, paths or journal contents.
			write(output, operation, "ERROR", "RESTORE_OPERATION_FAILED", null, null);
			return 3;
		}
	}

	private static Map<String, String> options(String[] args, String operation) {
		if (!OPERATIONS.contains(operation)) {
			throw new IllegalArgumentException();
		}
		Map<String, String> result = new HashMap<>();
		Set<String> allowed = Set.of("verify", "gate-status").contains(operation)
			? VERIFY_OPTIONS : Set.of("namespace");
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			int separator = argument.indexOf('=');
			if (!argument.startsWith("--") || separator < 3 || separator == argument.length() - 1) {
				throw new IllegalArgumentException();
			}
			String key = argument.substring(2, separator);
			if (!allowed.contains(key) || result.putIfAbsent(key, argument.substring(separator + 1)) != null) {
				throw new IllegalArgumentException();
			}
		}
		return result;
	}

	private static VerificationOptions verificationOptions(Map<String, String> options,
		Map<String, String> environment, Path directory, String namespace) {
		String generation = required(options, "restore-generation");
		String fingerprint = required(options, "expected-fingerprint");
		if (!UUID.fromString(generation).toString().equals(generation)
			|| !generation.equals(required(environment, "TINGLE_CHAT_RESTORE_GENERATION"))
			|| !fingerprint.matches("[a-f0-9]{64}") || !"true".equals(required(options, "fence-confirmed"))) {
			throw new IllegalArgumentException();
		}
		Path marker = configuredPath(environment, "TINGLE_CHAT_RESTORE_MARKER_FILE");
		if (marker.startsWith(directory) || directory.startsWith(marker.getParent())) {
			throw new IllegalArgumentException();
		}
		return new VerificationOptions(namespace, generation, fingerprint, marker,
			seconds(options, "wait-seconds", 180, 0, 900), seconds(options, "poll-seconds", 5, 1, 30));
	}

	private static int seconds(Map<String, String> options, String key, int fallback, int minimum, int maximum) {
		int seconds = options.containsKey(key) ? Integer.parseInt(options.get(key)) : fallback;
		if (seconds < minimum || seconds > maximum) {
			throw new IllegalArgumentException();
		}
		return seconds;
	}

	static String required(Map<String, String> values, String key) {
		String value = values.get(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException();
		}
		return value;
	}

	static Path configuredPath(Map<String, String> values, String key) {
		Path path = Path.of(required(values, key));
		if (!path.isAbsolute() || !path.normalize().equals(path)) {
			throw new IllegalArgumentException();
		}
		checkedPath(path);
		return path;
	}

	static void checkedPath(Path path) {
		for (Path part = path; part != null; part = part.getParent()) {
			if (Files.isSymbolicLink(part)) {
				throw new IllegalArgumentException();
			}
		}
	}

	static void ownerOnly(Path path, boolean directory) throws java.io.IOException {
		if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
		}
	}

	private static void write(PrintStream output, String operation, String status, String code,
		String fingerprint, Integer entries) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("operation", operation);
		result.put("status", status);
		if (code != null) {
			result.put("code", code);
		}
		if (fingerprint != null) {
			result.put("fingerprint", fingerprint);
			result.put("entries", entries);
		}
		output.println(MAPPER.writeValueAsString(result));
	}

	record VerificationOptions(String namespace, String generation, String fingerprint, Path marker,
		int waitSeconds, int pollSeconds) {
	}
}
