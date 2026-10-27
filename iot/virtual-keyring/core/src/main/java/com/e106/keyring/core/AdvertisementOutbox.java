package com.e106.keyring.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Serial radio scheduler. Repeats the same encrypted fragments so missed pieces can be recovered. */
public final class AdvertisementOutbox {
	private static final long WINDOW_MS = AdvertisementFrames.RECOVERY_WINDOW_MS;
	private final AdvertisementCodec codec;
	private final long session;
	private final Map<String, Transmission> outgoing = new LinkedHashMap<>();
	private Peer local;
	private Transmission discovery;
	private Transmission current;
	private String currentKey;
	private int frameIndex;
	private int roundRobin;
	private int transferId;

	public AdvertisementOutbox(AdvertisementCodec codec, Peer local, long session) {
		this.codec = codec;
		this.local = local;
		this.session = session;
		transferId = (int)AdvertisementCodec.newSession();
	}

	public void updateLocal(Peer peer) {
		if (!Arrays.equals(Packet.hello(local).encode(), Packet.hello(peer).encode())) {
			discovery = null;
			if (currentKey == null) {
				current = null;
			}
		}
		local = peer;
	}

	public void forgetPeer(UUID peer) {
		outgoing.entrySet().removeIf(entry -> entry.getKey().startsWith(peer + ":"));
		if (currentKey != null && currentKey.startsWith(peer + ":")) {
			current = null;
		}
	}

	public void send(UUID recipient, long recipientSession, Packet packet, long elapsed, long wallTime) {
		String kind = packet.encounter != null ? "event:" + packet.encounter.eventId
			: packet.type == Packet.NEW_REQUEST || (packet.type >= Packet.PREPARED && packet.type <= Packet.RECEIPT)
			? "event:" + packet.token
			: packet.type == Packet.RECHECK || packet.type == Packet.RECHECK_OK
			? "recheck" : Integer.toString(packet.type);
		String key = recipient + ":" + kind;
		outgoing.entrySet().removeIf(entry -> entry.getValue().expires <= elapsed);
		Transmission previous = outgoing.get(key);
		if (previous != null && previous.recipientSession == recipientSession
			&& Arrays.equals(previous.message.packet.encode(), packet.encode())) {
			return; // Do not replace a partial transfer with a new nonce/transfer ID on every retry.
		}
		if (previous == null && outgoing.size() >= 16) {
			throw new IllegalStateException("전송 대기열이 찼습니다. 잠시 후 재시도해주세요.");
		}
		outgoing.put(key, transmission(recipient, recipientSession, packet, elapsed, wallTime));
		if (key.equals(currentKey)) {
			current = null;
		}
	}

	public void acknowledge(UUID source, Packet packet) {
		if (packet.type == Packet.MATCH_RESULT) {
			remove(source + ":" + Packet.MATCH_DATA);
		} else if (packet.type == Packet.RECHECK || packet.type == Packet.RECHECK_OK) {
			remove(source + ":" + Packet.MATCH_DATA);
			remove(source + ":" + Packet.MATCH_RESULT);
			if (packet.type == Packet.RECHECK_OK) {
				remove(source + ":recheck");
			}
		} else if (packet.type == Packet.PREPARE) {
			remove(source + ":recheck");
		} else if (packet.type == Packet.RECEIPT) {
			Transmission pending = outgoing.get(source + ":event:" + packet.token);
			if (pending != null && pending.message.packet.type == Packet.DURABLE) {
				remove(source + ":event:" + packet.token);
			}
		}
		String key = packet.type == Packet.PONG ? source + ":" + Packet.PING
			: packet.type == Packet.STORED ? source + ":event:" + packet.encounter.eventId : "";
		Transmission sent = outgoing.get(key);
		if (sent == null) {
			return;
		}
		boolean matches = packet.type == Packet.PONG && sent.message.packet.type == Packet.PING
			&& packet.token.equals(sent.message.packet.token);
		matches |= packet.type == Packet.STORED && sent.message.packet.type == Packet.COMMIT
			&& packet.encounter.sameAs(sent.message.packet.encounter);
		if (matches) {
			outgoing.remove(key);
			if (key.equals(currentKey)) {
				current = null;
			}
		}
	}

	private void remove(String key) {
		outgoing.remove(key);
		if (key.equals(currentKey)) {
			current = null;
		}
	}

	public byte[] nextFrame(long elapsed, long wallTime) {
		outgoing.entrySet().removeIf(entry -> entry.getValue().expires <= elapsed);
		if (current == null || frameIndex >= current.frames.size() * 2 || current.expires <= elapsed) {
			// Hardware may deliver only a fraction of the fragments. Keep the same encrypted transfer
			// available for the whole bounded window, until an ACK or the next phase replaces it.
			List<String> keys = new ArrayList<>(outgoing.keySet());
			int slot = Math.floorMod(roundRobin++, keys.size() + 1);
			currentKey = slot == keys.size() ? null : keys.get(slot);
			if (currentKey == null) {
				if (discovery == null || discovery.expires <= elapsed) {
					discovery = transmission(AdvertisementCodec.BROADCAST, 0, Packet.hello(local), elapsed, wallTime);
				}
				current = discovery;
			} else {
				current = outgoing.get(currentKey);
			}
			frameIndex = 0;
		}
		int index = frameIndex++;
		return index < current.frames.size() ? current.frames.get(index)
			: AdvertisementRepair.frame(current.frames, current.repairSeed++);
	}

	/** Capture this alongside nextFrame: a later queue change must not relabel an in-flight radio callback. */
	public Message currentMessage() {
		return current == null ? null : current.message;
	}

	private Transmission transmission(UUID recipient, long recipientSession, Packet packet,
		long elapsed, long wallTime) {
		byte[] encrypted = codec.seal(local.deviceId, session, recipient, recipientSession, packet, wallTime);
		return new Transmission(AdvertisementFrames.split(encrypted, ++transferId), elapsed + WINDOW_MS,
			recipientSession, new Message(AdvertisementCodec.messageId(encrypted), recipient, packet));
	}

	public static final class Message {
		public final String id;
		public final UUID recipient;
		public final Packet packet;

		Message(String id, UUID recipient, Packet packet) {
			this.id = id;
			this.recipient = recipient;
			this.packet = packet;
		}
	}

	private static final class Transmission {
		private final List<byte[]> frames;
		private final long expires;
		private final long recipientSession;
		private final Message message;
		private int repairSeed;
		private Transmission(List<byte[]> frames, long expires, long recipientSession, Message message) {
			this.frames = frames;
			this.expires = expires;
			this.recipientSession = recipientSession;
			this.message = message;
		}
	}
}
