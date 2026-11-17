package com.e106.tingle.chat.deletion.restore.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RestoreOperationsCommandTest {
	private static final String SECRET = "SYNTHETIC_RESTORE_SECRET_DO_NOT_ECHO";
	@TempDir
	private Path directory;
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void initAndStatusUseOnlyTheValidatedFilesystemAndPrintNoDatabaseCredentials() throws Exception {
		Map<String, String> environment = environment(directory);
		Result initial = execute(environment, "journal-init", "--namespace=tingle_dev");
		assertThat(initial.exitCode()).isZero();
		assertThat(initial.json().path("status").asString()).isEqualTo("OK");
		assertThat(Files.readString(directory.resolve("namespace"))).isEqualTo("tingle_dev\n");
		if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			for (String file : List.of("namespace", "journal.lock", "intents.log", "head.sha256")) {
				assertThat(Files.getPosixFilePermissions(directory.resolve(file)))
					.containsOnly(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
			}
		}
		Result status = execute(environment, "journal-status", "--namespace=tingle_dev");
		assertThat(status.exitCode()).isZero();
		assertThat(status.json().path("fingerprint").asString()).matches("[a-f0-9]{64}");
		assertThat(status.json().path("entries").asInt()).isZero();
		assertThat(status.output()).doesNotContain(SECRET, "jdbc:", "PASSWORD", directory.toString());
	}

	@Test
	void explicitInitializationCannotOverwriteAnExistingJournalOrForeignDirectory() throws Exception {
		Files.writeString(directory.resolve("operator-owned"), "preserve");
		assertThat(execute(environment(directory), "journal-init", "--namespace=tingle_dev").exitCode())
			.isNotZero();
		assertThat(Files.readString(directory.resolve("operator-owned"))).isEqualTo("preserve");
		assertThat(Files.exists(directory.resolve("intents.log"))).isFalse();
		Path clean = Files.createDirectory(directory.resolve("clean"));
		assertThat(execute(environment(clean), "journal-init", "--namespace=tingle_dev").exitCode()).isZero();
		byte[] original = Files.readAllBytes(clean.resolve("head.sha256"));
		assertThat(execute(environment(clean), "journal-init", "--namespace=tingle_dev").exitCode()).isNotZero();
		assertThat(Files.readAllBytes(clean.resolve("head.sha256"))).isEqualTo(original);
	}

	@Test
	void malformedUnknownOrDuplicateOptionsFailBeforeCreatingJournalState() throws Exception {
		for (String[] arguments : List.of(new String[] {}, new String[] {"unknown-" + SECRET},
			new String[] {"journal-init"}, new String[] {"journal-init", "--namespace=other"},
			new String[] {"journal-init", "--namespace=tingle_dev", "--namespace=tingle_dev"},
			new String[] {"journal-init", "--namespace=tingle_dev", "--unknown=" + SECRET},
			new String[] {"verify", "--namespace=tingle_dev", "--expected-fingerprint=" + SECRET},
			new String[] {"verify", "--namespace=tingle_dev", "--restore-generation=not-a-uuid"})) {
			Result result = execute(environment(directory), arguments);
			assertThat(result.exitCode()).as(Arrays.toString(arguments)).isEqualTo(2);
			assertThat(result.output()).doesNotContain(SECRET, "jdbc:", directory.toString());
			assertThat(Files.exists(directory.resolve("intents.log"))).isFalse();
		}
	}

	@Test
	void namespaceMismatchAndDamagedJournalNeverReturnAUsableFingerprint() throws Exception {
		Map<String, String> environment = environment(directory);
		assertThat(execute(environment, "journal-init", "--namespace=tingle_dev").exitCode()).isZero();
		Result other = execute(environment, "journal-status", "--namespace=tingle");
		assertThat(other.exitCode()).isNotZero();
		assertThat(other.json().has("fingerprint")).isFalse();
		Files.delete(directory.resolve("head.sha256"));
		Result damaged = execute(environment, "journal-status", "--namespace=tingle_dev");
		assertThat(damaged.exitCode()).isNotZero();
		assertThat(damaged.json().has("fingerprint")).isFalse();
		assertThat(Files.exists(directory.resolve("head.sha256"))).isFalse();
	}

	@Test
	void symlinkedJournalCannotInitializeOrDiscloseItsTarget() throws Exception {
		Path target = Files.createDirectory(directory.resolve("target"));
		Path link = directory.resolve("link");
		try {
			Files.createSymbolicLink(link, target);
		} catch (IOException | UnsupportedOperationException exception) {
			assumeTrue(false, "Symbolic links are unavailable in this test environment");
		}
		Result result = execute(environment(link), "journal-init", "--namespace=tingle_dev");
		assertThat(result.exitCode()).isNotZero();
		assertThat(result.output()).doesNotContain(target.toString(), link.toString());
		assertThat(Files.exists(target.resolve("intents.log"))).isFalse();
	}

	@Test
	void verifyRequiresAnExplicitFenceAndMatchingGenerationBeforeAnyDatabaseAccess() throws Exception {
		Map<String, String> environment = environment(Files.createDirectory(directory.resolve("journal")));
		assertThat(execute(environment, "journal-init", "--namespace=tingle_dev").exitCode()).isZero();
		String generation = UUID.randomUUID().toString();
		Path markerDirectory = Files.createDirectory(directory.resolve("marker"));
		Path evidenceDirectory = Files.createDirectory(directory.resolve("evidence"));
		Path generationFile = evidenceDirectory.resolve("generation");
		Files.writeString(generationFile, generation);
		environment.put("TINGLE_CHAT_RESTORE_MARKER_FILE", markerDirectory.resolve("serving.marker").toString());
		environment.put("APP_BACKUP_EVIDENCE_NAMESPACE", "tingle_dev");
		environment.put("APP_BACKUP_EVIDENCE_SCOPE_ID", "test-db");
		environment.put("APP_BACKUP_EVIDENCE_APPROVAL_ID", "test-approval");
		environment.put("APP_BACKUP_EVIDENCE_FILE", evidenceDirectory.resolve("inventory.json").toString());
		environment.put("APP_BACKUP_EVIDENCE_GENERATION_FILE", generationFile.toString());
		environment.put("TINGLE_CHAT_RESTORE_GENERATION", UUID.randomUUID().toString());
		Result missingFence = execute(environment, "verify", "--namespace=tingle_dev",
			"--restore-generation=" + generation, "--expected-fingerprint=" + "0".repeat(64));
		assertThat(missingFence.exitCode()).isEqualTo(2);
		Result mismatched = execute(environment, "verify", "--namespace=tingle_dev",
			"--restore-generation=" + generation, "--expected-fingerprint=" + "0".repeat(64),
			"--fence-confirmed=true", "--wait-seconds=0");
		assertThat(mismatched.exitCode()).isEqualTo(2);
		assertThat(mismatched.output()).doesNotContain(SECRET, "jdbc:", generation);
		environment.put("TINGLE_CHAT_RESTORE_GENERATION", generation);
		for (String override : List.of("socketTimeout=0", "%73ocketTimeout=0", "connectTimeout=0",
			"user=" + SECRET, "password=" + SECRET)) {
			environment.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:1/tingle_dev?" + override);
			Result rejected = execute(environment, "verify", "--namespace=tingle_dev",
				"--restore-generation=" + generation, "--expected-fingerprint=" + "0".repeat(64),
				"--fence-confirmed=true", "--wait-seconds=0");
			assertThat(rejected.exitCode()).as("operator URL must not replace bounded JDBC settings").isEqualTo(2);
			assertThat(rejected.output()).doesNotContain(SECRET, "jdbc:", generation);
		}
		environment.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:1/tingle_dev");
		Result validOptions = execute(environment, "verify", "--namespace=tingle_dev",
			"--restore-generation=" + generation, "--expected-fingerprint=" + "0".repeat(64),
			"--fence-confirmed=true", "--wait-seconds=0");
		assertThat(validOptions.exitCode()).as("valid options reach the deliberately unreachable database")
			.isEqualTo(3);
		assertThat(validOptions.output()).doesNotContain(SECRET, "jdbc:", generation);
	}

	private Map<String, String> environment(Path journal) {
		Map<String, String> environment = new HashMap<>();
		environment.put("TINGLE_CHAT_DELETION_JOURNAL_DIRECTORY", journal.toString());
		environment.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:1/tingle_dev?password=" + SECRET);
		environment.put("SPRING_DATASOURCE_USERNAME", SECRET);
		environment.put("SPRING_DATASOURCE_PASSWORD", SECRET);
		return environment;
	}

	private Result execute(Map<String, String> environment, String... arguments) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		int exitCode = RestoreOperationsCommand.execute(arguments, environment,
			new PrintStream(bytes, true, StandardCharsets.UTF_8));
		String output = bytes.toString(StandardCharsets.UTF_8).strip();
		return new Result(exitCode, output, mapper.readTree(output));
	}

	private record Result(int exitCode, String output, JsonNode json) {
	}
}
