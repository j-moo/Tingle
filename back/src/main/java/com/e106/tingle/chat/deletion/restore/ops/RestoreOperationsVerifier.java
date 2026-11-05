package com.e106.tingle.chat.deletion.restore.ops;

import static com.e106.tingle.chat.deletion.restore.ops.RestoreOperationsCommand.*;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.e106.tingle.chat.deletion.ChatDeletionRepository;
import com.e106.tingle.chat.deletion.evidence.FileBackupEvidence;
import com.e106.tingle.chat.deletion.external.ExternalCleanupRepository;
import com.e106.tingle.chat.deletion.external.ExternalCleanupService;
import com.e106.tingle.chat.deletion.restore.FileDeletionJournal;
import com.e106.tingle.chat.deletion.restore.RestoreReplayService;
import com.e106.tingle.chat.deletion.restore.ops.RestoreOperationsCommand.VerificationOptions;

import tools.jackson.databind.json.JsonMapper;

/** Minimal JDBC-only runtime: no component scan, Spring application, scheduling, schema changes or HTTP. */
final class RestoreOperationsVerifier {
	private RestoreOperationsVerifier() {
	}

	static boolean verify(VerificationOptions options, Map<String, String> environment,
		FileDeletionJournal journal, JsonMapper mapper) throws Exception {
		if (!"true".equals(required(environment, "APP_BACKUP_EVIDENCE_ENABLED"))
			|| !options.namespace().equals(required(environment, "APP_BACKUP_EVIDENCE_NAMESPACE"))) {
			throw new IllegalArgumentException();
		}
		String scope = required(environment, "APP_BACKUP_EVIDENCE_SCOPE_ID");
		String approval = required(environment, "APP_BACKUP_EVIDENCE_APPROVAL_ID");
		if (!scope.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
			|| !approval.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")) {
			throw new IllegalArgumentException();
		}
		Path inventory = configuredPath(environment, "APP_BACKUP_EVIDENCE_FILE");
		Path generationFile = configuredPath(environment, "APP_BACKUP_EVIDENCE_GENERATION_FILE");
		Path journalDirectory = configuredPath(environment, "TINGLE_CHAT_DELETION_JOURNAL_DIRECTORY");
		if (inventory.equals(generationFile) || inventory.startsWith(journalDirectory)
			|| generationFile.startsWith(journalDirectory) || inventory.startsWith(options.marker().getParent())
			|| generationFile.startsWith(options.marker().getParent())) {
			throw new IllegalArgumentException();
		}
		if (!sameGeneration(generationFile, options.generation())) {
			throw new IllegalArgumentException();
		}
		String url = required(environment, "SPRING_DATASOURCE_URL");
		checkJdbcUrl(url);
		var source = new DriverManagerDataSource(url,
			required(environment, "SPRING_DATASOURCE_USERNAME"), required(environment, "SPRING_DATASOURCE_PASSWORD"));
		Properties timeouts = new Properties();
		timeouts.setProperty("connectTimeout", "5");
		timeouts.setProperty("socketTimeout", "15");
		timeouts.setProperty("cancelSignalTimeout", "3");
		timeouts.setProperty("loginTimeout", "5");
		source.setConnectionProperties(timeouts);
		JdbcTemplate jdbc = new JdbcTemplate(source);
		jdbc.setQueryTimeout(10);
		if (!options.namespace().equals(jdbc.queryForObject("select current_database()", String.class))) {
			throw new IllegalArgumentException();
		}
		Clock clock = Clock.systemUTC();
		var transactions = new DataSourceTransactionManager(source);
		transactions.setDefaultTimeout(10);
		var provider = new FileBackupEvidence(inventory, generationFile, options.namespace(), scope, approval,
			clock, mapper);
		var cleanup = new ExternalCleanupService(new ExternalCleanupRepository(jdbc, mapper), List.of(provider),
			clock, transactions);
		var replay = new RestoreReplayService(new ChatDeletionRepository(jdbc, mapper),
			new ExternalCleanupRepository(jdbc, mapper), cleanup, journal, clock, transactions,
			options.generation(), options.marker().toString());
		Path lockFile = options.marker().resolveSibling("restore.lock");
		checkedPath(lockFile);
		try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
			LinkOption.NOFOLLOW_LINKS)) {
			ownerOnly(lockFile, false);
			try (var lock = channel.tryLock()) {
				if (lock == null) {
					return false;
				}
				long deadline = System.nanoTime() + options.waitSeconds() * 1_000_000_000L;
				replay.fenceServing();
				do {
					if (Thread.currentThread().isInterrupted()
						|| !sameGeneration(generationFile, options.generation())) {
						replay.fenceServing();
						return false;
					}
					if (replay.replayAndVerify(options.fingerprint())) {
						return confirmGeneration(replay, () -> sameGeneration(generationFile, options.generation()));
					}
					long remaining = deadline - System.nanoTime();
					if (remaining <= 0) {
						return false;
					}
					Thread.sleep(Math.min(remaining / 1_000_000L + 1, options.pollSeconds() * 1000L));
				} while (true);
			}
		}
	}

	static boolean confirmGeneration(RestoreReplayService replay, GenerationCheck check) throws Exception {
		boolean confirmed = false;
		try {
			confirmed = check.matches();
			return confirmed;
		} finally {
			// A failed read must revoke the proof just written, exactly as a generation mismatch does.
			if (!confirmed) {
				replay.fenceServing();
			}
		}
	}

	@FunctionalInterface
	interface GenerationCheck {
		boolean matches() throws Exception;
	}

	private static boolean sameGeneration(Path file, String expected) throws Exception {
		checkedPath(file);
		return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= 200
			&& expected.equals(Files.readString(file).strip());
	}

	private static void checkJdbcUrl(String url) {
		if (!url.startsWith("jdbc:postgresql://")) {
			throw new IllegalArgumentException();
		}
		URI uri = URI.create(url.substring(5));
		if (uri.getHost() == null || uri.getUserInfo() != null) {
			throw new IllegalArgumentException();
		}
		Set<String> reserved = Set.of("connectTimeout", "socketTimeout", "cancelSignalTimeout", "loginTimeout",
			"user", "password");
		if (uri.getRawQuery() != null) {
			for (String field : uri.getRawQuery().split("&")) {
				String name = URLDecoder.decode(field.split("=", 2)[0], StandardCharsets.UTF_8);
				if (reserved.contains(name)) {
					throw new IllegalArgumentException();
				}
			}
		}
	}
}
