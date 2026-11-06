package com.e106.keyring.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Two radio lanes share one bounded queue. No recipient owns the radio for an entire message. */
public final class RadioOutbox {
	private static final long REQUEST_LIFETIME_MS = 150000;
	private final AdvertisementCodec codec;
	private final long session;
	private final Map<String, Transfer> outgoing = new LinkedHashMap<>();
	private final Map<UUID, Long> extendedPeers = new LinkedHashMap<>();
	private final Lane legacy = new Lane();
	private final Lane extended = new Lane();
	private Peer local;
	private boolean extendedEnabled;
	private boolean extendedReady;
	private int maximumLength;
	private int transferId;

	public RadioOutbox(AdvertisementCodec codec, Peer local, long session) {
		this.codec = codec;
		this.local = local;
		this.session = session;
		transferId = (int)AdvertisementCodec.newSession();
	}

	public void configureExtended(boolean enabled, int maximumLength) {
		extendedEnabled = enabled && ExtendedAdvertisement.supported(true, maximumLength);
		if (!extendedEnabled) {
			extendedReady = false;
		}
		this.maximumLength = Math.min(maximumLength, ExtendedAdvertisement.MAX_UPDATE_BYTES);
		legacy.discovery = null;
		extended.discovery = null;
	}

	public boolean usesExtended(UUID peer, long peerSession) {
		return extendedEnabled && extendedReady && Long.valueOf(peerSession).equals(extendedPeers.get(peer));
	}

	public void setExtendedReady() {
		extendedReady = extendedEnabled;
		legacy.discovery = null;
		extended.discovery = null;
	}

	public void remember(UUID peer, long peerSession, boolean supported) {
		if (supported) {
			extendedPeers.put(peer, peerSession);
		} else {
			extendedPeers.remove(peer);
		}
	}

	public void updateLocal(Peer peer) {
		if (!Arrays.equals(Packet.hello(local).encode(), Packet.hello(peer).encode())) {
			legacy.discovery = null;
			extended.discovery = null;
		}
		local = peer;
	}

	public void forgetPeer(UUID peer) {
		outgoing.values().removeIf(value -> value.recipient.equals(peer));
		extendedPeers.remove(peer);
	}

	public int size() {
		return outgoing.size();
	}

	public void send(UUID recipient, long recipientSession, Packet original, long now) {
		Packet packet = original.type == Packet.PREPARE ? Packet.preparation(original.encounter) : original;
		String kind = packet.type == Packet.PREPARE_REFERENCE
			|| (packet.type >= Packet.PREPARED && packet.type <= Packet.RECEIPT) ? "event:" + packet.token
			: packet.type == Packet.RECHECK || packet.type == Packet.RECHECK_OK || packet.type == Packet.RECHECK_BUSY
			? "recheck"
			: Integer.toString(packet.type);
		String key = recipient + ":" + kind;
		expire(now);
		Transfer previous = outgoing.get(key);
		if (previous != null && previous.recipientSession == recipientSession
			&& Arrays.equals(previous.packet.encode(), packet.encode())) {
			previous.requestedAt = now;
			return;
		}
		if (previous == null && outgoing.size() >= 128) {
			throw new IllegalStateException("RADIO_QUEUE_FULL");
		}
		outgoing.put(key, new Transfer(recipient, recipientSession, packet, now));
	}

	public void acknowledge(UUID source, Packet packet) {
		String prefix = source + ":";
		if (packet.type == Packet.RECHECK_BUSY) {
			Transfer request = outgoing.get(prefix + "recheck");
			if (request != null && packet.token.equals(request.packet.token)) {
				outgoing.remove(prefix + "recheck");
			}
		}
		if (packet.type == Packet.MATCH_RESULT) {
			outgoing.remove(prefix + Packet.MATCH_DATA);
		}
		if (packet.type == Packet.RECHECK || packet.type == Packet.RECHECK_OK) {
			outgoing.remove(prefix + Packet.MATCH_DATA);
			outgoing.remove(prefix + Packet.MATCH_RESULT);
		}
		if (packet.type == Packet.RECHECK_OK || packet.type == Packet.PREPARE_REFERENCE
			|| packet.type == Packet.PREPARE) {
			outgoing.remove(prefix + "recheck");
		}
		if (packet.type == Packet.RECEIPT) {
			Transfer previous = outgoing.get(prefix + "event:" + packet.token);
			if (previous != null && previous.packet.type == Packet.DURABLE) {
				outgoing.remove(prefix + "event:" + packet.token);
			}
		}
		if (packet.type == Packet.PONG) {
			Transfer ping = outgoing.get(prefix + Packet.PING);
			if (ping != null && packet.token.equals(ping.packet.token)) {
				outgoing.remove(prefix + Packet.PING);
			}
		}
	}

	public byte[] nextFrame(boolean full, long now, long wallTime) {
		if (full && !extendedEnabled) {
			throw new IllegalStateException("EXTENDED_LANE_DISABLED");
		}
		expire(now);
		Lane lane = full ? extended : legacy;
		List<Transfer> ready = new ArrayList<>();
		for (Transfer transfer : outgoing.values()) {
			if (usesExtended(transfer.recipient, transfer.recipientSession) == full
				&& (!full || transfer.packet.type != Packet.RECEIPT || now - transfer.lastSent >= 6000)) {
				ready.add(transfer);
			}
		}
		Transfer selected;
		if (ready.isEmpty() || lane.slots++ % 3 == 0) {
			if (lane.discovery == null) {
				lane.discovery = new Transfer(AdvertisementCodec.BROADCAST, 0,
					Packet.hello(local, extendedReady), now);
			}
			selected = lane.discovery;
		} else {
			if (!full) {
				// Two partial legacy transfers fit the recovery window even at a 600ms update cadence.
				// Leave other packets unsealed until their turn, then rotate after originals + repair.
				lane.active.removeIf(value -> !ready.contains(value));
				while (lane.active.size() < 2 && lane.active.size() < ready.size()) {
					Transfer next = ready.stream().filter(value -> !lane.active.contains(value))
						.min(java.util.Comparator.comparingLong(value -> value.turn)).orElseThrow();
					lane.active.add(next);
				}
				selected = lane.active.get(Math.floorMod(lane.cursor++, lane.active.size()));
			} else {
				selected = ready.get(Math.floorMod(lane.cursor++, ready.size()));
			}
		}
		boolean renew = selected.frames == null || selected.full != full
			|| now - selected.sealedAt >= REQUEST_LIFETIME_MS
			|| (full && now - selected.sealedAt >= 6000)
			|| (!full && selected.index >= selected.frames.size() * 2 && now - selected.sealedAt >= 30000);
		if (renew) {
			byte[] encrypted = codec.seal(local.deviceId, session, selected.recipient, selected.recipientSession,
				selected.packet, wallTime);
			selected.frames = full ? List.of(ExtendedAdvertisement.wrap(encrypted, maximumLength))
				: AdvertisementFrames.split(encrypted, ++transferId);
			selected.message = new AdvertisementOutbox.Message(AdvertisementCodec.messageId(encrypted),
				selected.recipient, selected.packet);
			selected.index = 0;
			selected.sealedAt = now;
			selected.full = full;
		}
		lane.message = selected.message;
		selected.lastSent = now;
		int index = selected.index++;
		if (!full && selected != lane.discovery && selected.index >= selected.frames.size() * 2) {
			lane.active.remove(selected);
			selected.turn = ++lane.turn;
		}
		return full || index < selected.frames.size() ? selected.frames.get(full ? 0 : index)
			: AdvertisementRepair.frame(selected.frames, index - selected.frames.size());
	}

	public AdvertisementOutbox.Message currentMessage(boolean full) {
		return (full ? extended : legacy).message;
	}

	private void expire(long now) {
		outgoing.values().removeIf(value -> now - value.requestedAt >= REQUEST_LIFETIME_MS);
	}

	private static final class Lane {
		private final List<Transfer> active = new ArrayList<>();
		private long slots;
		private long turn;
		private int cursor;
		private Transfer discovery;
		private AdvertisementOutbox.Message message;
	}

	private static final class Transfer {
		private final UUID recipient;
		private final long recipientSession;
		private final Packet packet;
		private long requestedAt;
		private long sealedAt;
		private long lastSent = -6000;
		private long turn;
		private List<byte[]> frames;
		private AdvertisementOutbox.Message message;
		private boolean full;
		private int index;

		private Transfer(UUID recipient, long recipientSession, Packet packet, long now) {
			this.recipient = recipient;
			this.recipientSession = recipientSession;
			this.packet = packet;
			requestedAt = now;
		}
	}
}
