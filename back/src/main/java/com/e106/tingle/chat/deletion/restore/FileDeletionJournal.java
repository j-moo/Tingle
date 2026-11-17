package com.e106.tingle.chat.deletion.restore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.e106.tingle.chat.deletion.ChatDeletionState;
import com.e106.tingle.chat.deletion.ChatDeletionState.Status;

import tools.jackson.databind.json.JsonMapper;

/**
 * Metadata-only intent ledger outside the restored database. The directory and its parents must be writable
 * only by the privileged journal/restore process. Hashes detect accidental damage, not a malicious owner.
 * Here privileged means the trusted retention application identity and operator restore tool, not necessarily root.
 * Ordinary API clients cannot provision a journal. Snapshot readers also need WRITE access to journal.lock.
 * Missing files, interrupted append/head updates and damaged chains block deletion and restore verification.
 */
@Component
public class FileDeletionJournal implements DeletionJournal {

	private static final long MAXIMUM_BYTES = 64L * 1024 * 1024;
	private static final int MAXIMUM_RECORD_BYTES = 65536;
	private static final String EMPTY_HASH = "0".repeat(64);
	private static final Map<Path, Object> MONITORS = new ConcurrentHashMap<>();
	private final String directory;
	private final JsonMapper mapper;

	public FileDeletionJournal(@Value("${tingle.chat.deletion.journal-directory:}") String directory,
		JsonMapper mapper) {
		// 기본값은 사진 영구 볼륨 안의 폴더다(application.yml). 빈 값으로 덮어쓰면 기동하지 않는다.
		if (directory == null || directory.isBlank()) {
			throw new IllegalStateException("tingle.chat.deletion.journal-directory 값이 비어 있습니다");
		}
		this.directory = directory;
		this.mapper = mapper;
	}

	/**
	 * 빈 폴더에 새 저널을 만든다. 파일이 하나라도 있으면 덮어쓰지 않고 거절한다.
	 * 운영 명령(journal-init)과 첫 삭제 기록(record)이 함께 쓴다.
	 */
	public static void initialize(Path directory) {
		try {
			Path root = checkedRoot(directory);
			if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
				throw unavailable();
			}
			try (var children = Files.list(root)) {
				if (children.findAny().isPresent()) {
					throw unavailable();
				}
			}
			writeNew(root.resolve("journal.lock"), new byte[0]);
			writeNew(root.resolve("intents.log"), new byte[0]);
			writeNew(root.resolve("head.sha256"), (EMPTY_HASH + "\n").getBytes(StandardCharsets.US_ASCII));
		} catch (IOException exception) {
			throw unavailable();
		}
	}

	@Override
	public void record(ChatDeletionState intent) {
		ChatDeletionState entry = canonical(intent);
		initializeIfAbsent();
		locked(snapshot -> {
			boolean duplicate = false;
			for (ChatDeletionState previous : snapshot.entries()) {
				if (previous.generation().equals(entry.generation())
					&& previous.context().matchingId() == entry.context().matchingId()) {
					if (!sameScopeAndDue(previous, entry)) {
						throw unavailable();
					}
					if (previous.attempt() == entry.attempt()) {
						if (previous.equals(entry)) {
							duplicate = true;
						}
						if (!supersedes(previous, entry)) {
							throw unavailable();
						}
					}
				}
			}
			if (duplicate) {
				return null;
			}
			byte[] json = mapper.writeValueAsBytes(entry);
			if (json.length > MAXIMUM_RECORD_BYTES) {
				throw unavailable();
			}
			String next = hash(snapshot.fingerprint(), json);
			byte[] line = (next + "\t" + Base64.getEncoder().encodeToString(json) + "\n")
				.getBytes(StandardCharsets.US_ASCII);
			Path root = checkedRoot(Path.of(directory));
			try {
				if (Files.size(root.resolve("intents.log")) + line.length > MAXIMUM_BYTES) {
					throw unavailable();
				}
				try (var log = open(root.resolve("intents.log"), StandardOpenOption.WRITE)) {
					log.position(log.size());
					writeFully(log, line);
					log.force(true);
				}
				// Stable preprovisioned file avoids replacement/parent-directory durability dependence.
				// A torn head update never matches the validated full chain and therefore blocks on reopen.
				try (var head = open(root.resolve("head.sha256"), StandardOpenOption.WRITE)) {
					writeFully(head, (next + "\n").getBytes(StandardCharsets.US_ASCII));
					head.truncate(65);
					head.force(true);
				}
			} catch (IOException exception) {
				throw unavailable();
			}
			return null;
		});
	}

	/**
	 * 첫 삭제 기록 때 폴더가 없거나 비어 있으면 저널을 만든다(서버에서 따로 할 일이 없다).
	 * 이미 파일이 있으면 그대로 두며, 일부만 있는 손상 상태는 만들지 않고 삭제를 막는다. 읽기(snapshot)는 만들지 않는다.
	 */
	private void initializeIfAbsent() {
		Path root = checkedRoot(Path.of(directory));
		synchronized (MONITORS.computeIfAbsent(root, unused -> new Object())) {
			try {
				Files.createDirectories(root);
				try (var children = Files.list(root)) {
					if (children.findAny().isPresent()) {
						return;
					}
				}
			} catch (IOException exception) {
				throw unavailable();
			}
			// 다른 프로세스(블루·그린)가 동시에 만들면 한쪽은 실패하고 다음 시도에서 만든 저널을 쓴다.
			initialize(root);
		}
	}

	@Override
	public Snapshot snapshot() {
		return locked(Function.identity());
	}

	@Override
	public <T> T locked(Function<Snapshot, T> action) {
		if (directory.isBlank()) {
			throw unavailable();
		}
		Path root = checkedRoot(Path.of(directory));
		synchronized (MONITORS.computeIfAbsent(root, unused -> new Object())) {
			try (var lock = open(root.resolve("journal.lock"), StandardOpenOption.WRITE);
				var held = lock.lock()) {
				return action.apply(read(root));
			} catch (IOException exception) {
				throw unavailable();
			}
		}
	}

	private Snapshot read(Path root) {
		try {
			Path logPath = root.resolve("intents.log");
			if (!Files.isRegularFile(logPath, LinkOption.NOFOLLOW_LINKS)
				|| !Files.isRegularFile(root.resolve("head.sha256"), LinkOption.NOFOLLOW_LINKS)
				|| Files.size(logPath) > MAXIMUM_BYTES || Files.size(root.resolve("head.sha256")) != 65) {
				throw unavailable();
			}
			String text;
			String anchor;
			try (var log = Files.newInputStream(logPath, LinkOption.NOFOLLOW_LINKS);
				var head = Files.newInputStream(root.resolve("head.sha256"), LinkOption.NOFOLLOW_LINKS)) {
				text = new String(log.readNBytes((int)MAXIMUM_BYTES + 1), StandardCharsets.US_ASCII);
				anchor = new String(head.readNBytes(66), StandardCharsets.US_ASCII);
			}
			if (text.length() > MAXIMUM_BYTES || (!text.isEmpty() && !text.endsWith("\n"))) {
				throw unavailable();
			}
			Map<JournalKey, ChatDeletionState> entries = new LinkedHashMap<>();
			String previous = EMPTY_HASH;
			String[] lines = text.split("\n", -1);
			for (int index = 0; index < lines.length - 1; index++) {
				String line = lines[index];
				if (line.length() > MAXIMUM_RECORD_BYTES * 2 || line.length() < 67 || line.charAt(64) != '\t') {
					throw unavailable();
				}
				byte[] json = Base64.getDecoder().decode(line.substring(65));
				if (json.length > MAXIMUM_RECORD_BYTES || !hash(previous, json).equals(line.substring(0, 64))) {
					throw unavailable();
				}
				ChatDeletionState entry = mapper.readValue(json, ChatDeletionState.class);
				if (!entry.equals(canonical(entry))) {
					throw unavailable();
				}
				JournalKey key = new JournalKey(entry.context().matchingId(), entry.generation(), entry.attempt());
				for (ChatDeletionState earlier : entries.values()) {
					if (earlier.generation().equals(entry.generation())
						&& earlier.context().matchingId() == entry.context().matchingId()
						&& !sameScopeAndDue(earlier, entry)) {
						throw unavailable();
					}
				}
				ChatDeletionState prior = entries.get(key);
				if (prior != null && !supersedes(prior, entry)) {
					throw unavailable();
				}
				entries.put(key, entry);
				previous = line.substring(0, 64);
			}
			if (!anchor.equals(previous + "\n")) {
				throw unavailable();
			}
			return new Snapshot(List.copyOf(entries.values()), previous);
		} catch (IOException | IllegalArgumentException exception) {
			throw unavailable();
		}
	}

	private static ChatDeletionState canonical(ChatDeletionState intent) {
		if (intent == null || !intent.inventory().authorizesDeletion()
			|| (intent.status() != Status.RUNNING && intent.status() != Status.DB_DELETED)
			|| (intent.status() == Status.DB_DELETED && intent.dbDeletedAt() == null)) {
			throw unavailable();
		}
		return new ChatDeletionState(1, intent.generation(), intent.context(), intent.status(), intent.attempt(),
			null, null, intent.inventory(), null, intent.status() == Status.DB_DELETED ? intent.dbDeletedAt() : null);
	}

	private static boolean supersedes(ChatDeletionState previous, ChatDeletionState entry) {
		return previous.context().equals(entry.context()) && sameScopeAndDue(previous, entry)
			&& (previous.status() == Status.RUNNING
			|| (entry.status() == Status.DB_DELETED && !entry.dbDeletedAt().isBefore(previous.dbDeletedAt())));
	}

	private static boolean sameScopeAndDue(ChatDeletionState previous, ChatDeletionState entry) {
		var before = previous.context();
		var after = entry.context();
		return before.matchingId() == after.matchingId() && before.chatId() == after.chatId()
			&& before.createdAt().equals(after.createdAt()) && before.closedAt().equals(after.closedAt())
			&& before.sourceFingerprint().equals(after.sourceFingerprint())
			&& !after.dueAt().isBefore(before.dueAt()) && previous.inventory().equals(entry.inventory());
	}

	private record JournalKey(long matchingId, UUID generation, int attempt) {
	}

	private static Path checkedRoot(Path directory) {
		Path root = directory.toAbsolutePath().normalize();
		for (Path part = root; part != null; part = part.getParent()) {
			if (Files.isSymbolicLink(part)) {
				throw unavailable();
			}
		}
		return root;
	}

	private static FileChannel open(Path path, StandardOpenOption option) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw unavailable();
		}
		return FileChannel.open(path, option, LinkOption.NOFOLLOW_LINKS);
	}

	private static void writeNew(Path path, byte[] content) throws IOException {
		try (var file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
			LinkOption.NOFOLLOW_LINKS)) {
			writeFully(file, content);
			file.force(true);
		}
	}

	private static void writeFully(FileChannel file, byte[] content) throws IOException {
		ByteBuffer buffer = ByteBuffer.wrap(content);
		while (buffer.hasRemaining()) {
			file.write(buffer);
		}
	}

	private static String hash(String previous, byte[] content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(previous.getBytes(StandardCharsets.US_ASCII));
			digest.update((byte)'\n');
			return HexFormat.of().formatHex(digest.digest(content));
		} catch (NoSuchAlgorithmException exception) {
			throw unavailable();
		}
	}

	private static IllegalStateException unavailable() {
		return new IllegalStateException("Deletion journal is unavailable or inconsistent");
	}
}
