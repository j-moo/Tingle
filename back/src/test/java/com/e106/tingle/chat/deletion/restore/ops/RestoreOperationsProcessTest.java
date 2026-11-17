package com.e106.tingle.chat.deletion.restore.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The actual packaged entrypoint must not start the web application, JPA or scheduled jobs. */
class RestoreOperationsProcessTest {
	private static final String SECRET = "SYNTHETIC_PROCESS_SECRET_DO_NOT_ECHO";
	@TempDir
	private Path directory;
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void packagedJournalCommandsFinishWithAnOccupiedHttpPortAndUnreachableDatabase() throws Exception {
		Path journal = Files.createDirectory(directory.resolve("journal with spaces"));
		try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			Result initialized = run(journal, occupied.getLocalPort(), "journal-init", "--namespace=tingle_dev");
			assertThat(initialized.exitCode()).isZero();
			assertThat(initialized.json().path("status").asString()).isEqualTo("OK");
			Result status = run(journal, occupied.getLocalPort(), "journal-status", "--namespace=tingle_dev");
			assertThat(status.exitCode()).isZero();
			assertThat(status.json().path("fingerprint").asString()).matches("[a-f0-9]{64}");
			assertThat(status.json().path("entries").asInt()).isZero();
			assertThat(status.output()).doesNotContain(SECRET, "Tomcat", "Hikari", "Started BackendApplication",
				"Spring Boot", "jdbc:");
		}
	}

	@Test
	void packagedInvalidOptionsAreStructuredErrorsAndNeverEchoArgumentsOrStartTheApplication() throws Exception {
		Path journal = Files.createDirectory(directory.resolve("uninitialized"));
		Result invalid = run(journal, 1, "verify", "--namespace=tingle_dev", "--unknown=" + SECRET);
		assertThat(invalid.exitCode()).isEqualTo(2);
		assertThat(invalid.json().path("status").asString()).isEqualTo("ERROR");
		assertThat(invalid.output()).doesNotContain(SECRET, "Tomcat", "Hikari", "Spring Boot", "jdbc:");
		assertThat(Files.exists(journal.resolve("intents.log"))).isFalse();
	}

	@Test
	void packagedDatabaseConnectionFailurePrintsOnlyASanitizedStructuredError() throws Exception {
		Path journal = Files.createDirectory(directory.resolve("journal"));
		Result initialized = run(journal, 1, "journal-init", "--namespace=tingle_dev");
		assertThat(initialized.exitCode()).isZero();
		String generation = UUID.randomUUID().toString();
		Path marker = Files.createDirectory(directory.resolve("restore")).resolve("serving.marker");
		Path evidence = Files.createDirectory(directory.resolve("evidence"));
		Path generationFile = evidence.resolve("generation");
		Path inventory = evidence.resolve("inventory.json");
		Files.writeString(generationFile, generation);
		Files.writeString(inventory, mapper.writeValueAsString(Map.of("schemaVersion", 1,
			"namespace", "tingle_dev", "scopeId", "test-db", "restoreGeneration", generation,
			"inspectedAt", Instant.now().toString(), "complete", true, "entries", List.of())));
		Map<String, String> environment = Map.ofEntries(
			Map.entry("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:1/tingle_dev"),
			Map.entry("TINGLE_CHAT_RESTORE_GENERATION", generation),
			Map.entry("TINGLE_CHAT_RESTORE_MARKER_FILE", marker.toString()),
			Map.entry("APP_BACKUP_EVIDENCE_NAMESPACE", "tingle_dev"),
			Map.entry("APP_BACKUP_EVIDENCE_SCOPE_ID", "test-db"),
			Map.entry("APP_BACKUP_EVIDENCE_APPROVAL_ID", "test-approval"),
			Map.entry("APP_BACKUP_EVIDENCE_FILE", inventory.toString()),
			Map.entry("APP_BACKUP_EVIDENCE_GENERATION_FILE", generationFile.toString()));
		Result failed = run(journal, 1, environment, "verify", "--namespace=tingle_dev",
			"--restore-generation=" + generation,
			"--expected-fingerprint=" + initialized.json().path("fingerprint").asString(),
			"--fence-confirmed=true", "--wait-seconds=0");
		assertThat(failed.exitCode()).isEqualTo(3);
		assertThat(failed.json().path("status").asString()).isEqualTo("ERROR");
		assertThat(failed.output().lines().count()).isEqualTo(1);
		assertThat(failed.output()).doesNotContain(SECRET, "jdbc:", directory.toString(), "Exception", "Spring Boot");
	}

	private Result run(Path journal, int occupiedPort, String... arguments) throws Exception {
		return run(journal, occupiedPort, Map.of(), arguments);
	}

	private Result run(Path journal, int occupiedPort, Map<String, String> overrides, String... arguments)
		throws Exception {
		String jar = System.getProperty("tingle.restore-ops.jar");
		assertThat(jar).as("test task must supply the packaged bootJar").isNotBlank();
		assertThat(Files.isRegularFile(Path.of(jar))).isTrue();
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		if (!Files.isRegularFile(java)) {
			java = java.resolveSibling("java.exe");
		}
		List<String> command = new ArrayList<>(List.of(java.toString(), "-jar", jar, "chat-restore"));
		command.addAll(List.of(arguments));
		Path output = Files.createTempFile(directory, "process-", ".log");
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
		Map<String, String> environment = builder.environment();
		for (String option : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
			environment.remove(option);
		}
		environment.put("TINGLE_CHAT_DELETION_JOURNAL_DIRECTORY", journal.toString());
		environment.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:1/tingle_dev?password=" + SECRET);
		environment.put("SPRING_DATASOURCE_USERNAME", SECRET);
		environment.put("SPRING_DATASOURCE_PASSWORD", SECRET);
		environment.put("SERVER_PORT", Integer.toString(occupiedPort));
		environment.put("TINGLE_CHAT_DELETION_ENABLED", "true");
		environment.putAll(overrides);
		Process process = builder.start();
		try {
			assertThat(process.waitFor(25, TimeUnit.SECONDS)).as("one-shot command must exit without an app server")
				.isTrue();
			String text = Files.readString(output, StandardCharsets.UTF_8).strip();
			return new Result(process.exitValue(), text, mapper.readTree(text));
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
			}
		}
	}

	private record Result(int exitCode, String output, JsonNode json) {
	}
}
