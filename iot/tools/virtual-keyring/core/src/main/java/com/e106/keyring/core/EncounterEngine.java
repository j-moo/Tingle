package com.e106.keyring.core;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.e106.keyring.core.SavedEncounter.Phase;

/** Serial executor only. Every application ACK follows durable storage. */
public final class EncounterEngine {
	// The outbox coalesces retries without restarting an in-flight fragmented message.
	private static final long AUTOMATIC_RETRY_MS = 30000;
	private final RecordStore store;
	private final Consumer<Packet> sender;
	private final Consumer<String> observer;
	private final LongSupplier clock;
	private Peer local;
	private Peer remote;
	private boolean initiator;
	private boolean connected;
	private UUID pendingPing;
	private UUID requestedEvent;
	private UUID automaticEventId;
	private long nextAutomaticAttempt;

	public EncounterEngine(Peer local, RecordStore store, Consumer<Packet> sender,
		Consumer<String> observer, LongSupplier clock) {
		this.local = local;
		this.store = store;
		this.sender = sender;
		this.observer = observer;
		this.clock = clock;
	}

	public void connect(boolean initiator) {
		connect(initiator, UUID.randomUUID());
	}

	public void connect(boolean initiator, UUID automaticEventId) {
		this.initiator = initiator;
		this.automaticEventId = automaticEventId;
		connected = true;
		remote = null;
		nextAutomaticAttempt = 0;
		sender.accept(Packet.hello(local));
	}

	/** One automatic event per pair of ON sessions, even if discovery state is evicted and rebuilt. */
	public static UUID sessionEventId(UUID local, long localSession, UUID remote, long remoteSession) {
		String first = local + ":" + localSession;
		String second = remote + ":" + remoteSession;
		String pair = local.toString().compareTo(remote.toString()) < 0 ? first + "/" + second : second + "/" + first;
		return UUID.nameUUIDFromBytes(("E106-lab-on-1/" + pair).getBytes(StandardCharsets.UTF_8));
	}

	public UUID getAutomaticEventId() {
		return automaticEventId;
	}

	public void disconnect() {
		connected = false;
		remote = null;
		pendingPing = null;
		requestedEvent = null;
	}

	public void updateLocal(Peer local) {
		if (!this.local.deviceId.equals(local.deviceId)) {
			throw new IllegalArgumentException("LOCAL_ID_CHANGED");
		}
		this.local = local;
		if (connected) {
			sender.accept(Packet.hello(local));
		}
	}

	public Peer getRemote() {
		return remote;
	}

	/** Called only after an authenticated discovery, with a monotonic elapsed time. */
	public void onDiscovery(long elapsedMillis) {
		if (!connected || remote == null || !local.ready || !remote.ready) {
			return;
		}
		SavedEncounter pending = store.findPendingPair(local.deviceId, remote.deviceId);
		if (pending == null) {
			if (store.find(automaticEventId) != null || !initiator || requestedEvent != null) {
				return;
			}
		}
		if (elapsedMillis < nextAutomaticAttempt) {
			return;
		}
		// Apply backoff even when storage fails; never busy-loop writes on every received beacon.
		nextAutomaticAttempt = elapsedMillis + AUTOMATIC_RETRY_MS;
		if (pending == null) {
			startNew();
			observer.accept("상대 발견 · 시험 만남 자동 저장 시작");
		} else {
			saveOrRetry();
			observer.accept("미완료 만남 자동 재시도 · 기존 이벤트 유지");
		}
	}

	public void ping() {
		requirePeer();
		pendingPing = UUID.randomUUID();
		sender.accept(Packet.ping(Packet.PING, pendingPing));
	}

	public void saveOrRetry() {
		requirePeer();
		SavedEncounter previous = store.findPendingPair(local.deviceId, remote.deviceId);
		if (previous == null) {
			previous = store.findPair(local.deviceId, remote.deviceId);
		}
		if (previous != null) {
			Encounter encounter = previous.encounter;
			if (encounter.deviceA.equals(local.deviceId)) {
				int type = previous.phase == Phase.PREPARED ? Packet.PROPOSE : Packet.COMMIT;
				sender.accept(Packet.event(type, encounter));
			} else {
				sender.accept(Packet.event(previous.phase == Phase.PREPARED ? Packet.READY : Packet.STORED, encounter));
			}
			observer.accept("기존 이벤트를 재전송했습니다. 새 만남은 만들지 않습니다.");
			return;
		}
		startNew();
	}

	public void retryLast() {
		requirePeer();
		if (requestedEvent != null) {
			sender.accept(Packet.ping(Packet.NEW_REQUEST, requestedEvent));
			return;
		}
		if (store.findPair(local.deviceId, remote.deviceId) == null) {
			throw new IllegalStateException("자동 저장할 상대의 준비·응답을 기다리고 있습니다.");
		}
		saveOrRetry();
	}

	public void startNew() {
		requirePeer();
		if (store.findPendingPair(local.deviceId, remote.deviceId) != null) {
			throw new IllegalStateException("미완료 만남이 있습니다. 마지막 만남 재시도를 먼저 눌러주세요.");
		}
		if (requestedEvent != null) {
			sender.accept(Packet.ping(Packet.NEW_REQUEST, requestedEvent));
			observer.accept("같은 새 만남 요청 재전송 · 상대 응답 대기");
			return;
		}
		if (!local.ready || !remote.ready) {
			throw new IllegalStateException("양쪽에서 시험 설정 적용을 먼저 눌러주세요.");
		}
		UUID eventId = store.find(automaticEventId) == null ? automaticEventId : UUID.randomUUID();
		if (initiator) {
			create(eventId);
		} else {
			requestedEvent = eventId;
			sender.accept(Packet.ping(Packet.NEW_REQUEST, eventId));
			observer.accept("새 시험 만남 요청 · 상대의 준비 기록 대기");
		}
	}

	private void create(UUID eventId) {
		if (!local.ready || !remote.ready) {
			throw new IllegalStateException("양쪽에서 시험 설정 적용을 먼저 눌러주세요.");
		}
		Encounter encounter = new Encounter(eventId, local, remote, clock.getAsLong());
		store.save(encounter, Phase.PREPARED);
		sender.accept(Packet.event(Packet.PROPOSE, encounter));
		observer.accept("내 준비 기록 저장 · 상대 응답 대기");
	}

	public void receive(Packet packet) {
		if (!connected) {
			return;
		}
		if (packet.type == Packet.HELLO) {
			if (packet.peer.deviceId.equals(local.deviceId)
				|| (remote != null && !remote.deviceId.equals(packet.peer.deviceId))) {
				throw new IllegalArgumentException("PEER_ID_CONFLICT");
			}
			boolean firstHello = remote == null;
			boolean changed = firstHello || remote.ready != packet.peer.ready;
			remote = packet.peer;
			if (firstHello) {
				sender.accept(Packet.hello(local));
			}
			if (changed) {
				observer.accept(remote.ready ? "상대 시험 설정 준비됨" : "상대 시험 설정 적용 대기");
			}
			return;
		}
		if (packet.type == Packet.ERROR) {
			observer.accept("상대 오류: " + packet.error + " · 기록 보존, 확인 후 재시도");
			return;
		}
		requirePeer();
		if (packet.type == Packet.NEW_REQUEST) {
			if (!initiator) {
				throw new IllegalArgumentException("NOT_EVENT_COORDINATOR");
			}
			SavedEncounter existing = store.find(packet.token);
			if (existing != null) {
				if (!existing.encounter.deviceA.equals(local.deviceId)
					|| !existing.encounter.deviceB.equals(remote.deviceId)) {
					throw new IllegalArgumentException("WRONG_PARTICIPANT");
				}
				sender.accept(Packet.event(existing.phase == Phase.PREPARED ? Packet.PROPOSE : Packet.COMMIT,
					existing.encounter));
			} else if (store.findPendingPair(local.deviceId, remote.deviceId) != null) {
				saveOrRetry();
			} else {
				create(packet.token);
			}
		} else if (packet.type == Packet.PING) {
			sender.accept(Packet.ping(Packet.PONG, packet.token));
			observer.accept("PING 수신 · PONG 응답");
		} else if (packet.type == Packet.PONG) {
			if (packet.token.equals(pendingPing)) {
				pendingPing = null;
				observer.accept("통신 시험 성공 · PING/PONG 왕복 확인");
			}
		} else {
			receiveEncounter(packet);
		}
	}

	private void receiveEncounter(Packet packet) {
		Encounter encounter = packet.encounter;
		if (encounter == null || !encounter.peerOf(local.deviceId).equals(remote.deviceId)) {
			throw new IllegalArgumentException("WRONG_PARTICIPANT");
		}
		SavedEncounter previous = store.find(encounter.eventId);
		if (previous != null && !previous.encounter.sameAs(encounter)) {
			throw new IllegalArgumentException("EVENT_CONFLICT");
		}
		boolean owner = encounter.deviceA.equals(local.deviceId);
		if (packet.type == Packet.PROPOSE && !owner) {
			if (previous == null) {
				if (initiator) {
					throw new IllegalArgumentException("NOT_EVENT_COORDINATOR");
				}
				if (!local.ready || !remote.ready || !encounter.snapshotB.equals(local.snapshotId)
					|| !encounter.snapshotA.equals(remote.snapshotId) || encounter.versionA != remote.settingsVersion
					|| encounter.versionB != local.settingsVersion) {
					throw new IllegalArgumentException("SETTINGS_NOT_READY");
				}
				previous = store.save(encounter, Phase.PREPARED);
			}
			requestedEvent = null;
			sender.accept(Packet.event(previous.phase == Phase.CONFIRMED ? Packet.STORED : Packet.READY, encounter));
		} else if (packet.type == Packet.READY && owner && previous != null) {
			store.save(encounter, Phase.COMMITTED);
			sender.accept(Packet.event(Packet.COMMIT, encounter));
		} else if (packet.type == Packet.COMMIT && !owner && previous != null) {
			store.save(encounter, Phase.CONFIRMED);
			sender.accept(Packet.event(Packet.STORED, encounter));
		} else if (packet.type == Packet.STORED && owner && previous != null) {
			// STORED proves that the peer received our earlier durable COMMIT, including after restart.
			if (previous.phase == Phase.PREPARED) {
				throw new IllegalArgumentException("UNEXPECTED_STORED");
			}
			store.save(encounter, Phase.CONFIRMED);
		} else {
			throw new IllegalArgumentException("UNEXPECTED_EVENT_PHASE");
		}
		observer.accept("만남 기록 갱신 · " + encounter.eventId.toString().substring(0, 8));
	}

	private void requirePeer() {
		if (!connected || remote == null) {
			throw new IllegalStateException("광고·스캔을 시작하고 같은 그룹의 상대 발견을 기다려주세요.");
		}
	}
}
