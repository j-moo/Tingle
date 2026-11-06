package com.e106.keyring.core;

import java.util.List;

/** Continuous own-data advertisements. No peer, acknowledgement or handshake queue. */
public final class ProfileBroadcaster {
	private final AdvertisementCodec codec;
	private final DemoProfile local;
	private final long session;
	private final Lane legacy = new Lane();
	private final Lane extended = new Lane();
	private int maximumLength;
	private boolean extendedEnabled;
	private int transferId;

	public ProfileBroadcaster(AdvertisementCodec codec, DemoProfile local, long session) {
		this.codec = codec;
		this.local = local;
		this.session = session;
		transferId = (int)AdvertisementCodec.newSession();
	}

	public void configureExtended(boolean enabled, int maximumLength) {
		extendedEnabled = enabled && ExtendedAdvertisement.supported(true, maximumLength);
		this.maximumLength = Math.min(maximumLength, ExtendedAdvertisement.MAX_UPDATE_BYTES);
	}

	public byte[] nextFrame(boolean full, long now, long wallTime) {
		if (full && !extendedEnabled) {
			throw new IllegalStateException("EXTENDED_LANE_DISABLED");
		}
		Lane lane = full ? extended : legacy;
		// Retain legacy ciphertext while missing fragments are repaired. This never delays local matching.
		if (lane.frames == null || now - lane.createdAt >= (full ? 30000 : 120000)) {
			Packet packet = Packet.broadcastProfile(local);
			byte[] encrypted = codec.seal(local.keyring.deviceId, session, AdvertisementCodec.BROADCAST, 0,
				packet, wallTime);
			lane.frames = full ? List.of(ExtendedAdvertisement.wrap(encrypted, maximumLength))
				: AdvertisementFrames.split(encrypted, ++transferId);
			lane.message = new AdvertisementOutbox.Message(AdvertisementCodec.messageId(encrypted),
				AdvertisementCodec.BROADCAST, packet);
			lane.createdAt = now;
			lane.index = 0;
		}
		int index = lane.index++;
		if (full) {
			return lane.frames.get(0);
		}
		// Alternate original and repair rounds; a newly arrived receiver can recover in any round.
		int cycle = Math.floorMod(index, lane.frames.size() * 2);
		return cycle < lane.frames.size() ? lane.frames.get(cycle)
			: AdvertisementRepair.frame(lane.frames, index - lane.frames.size());
	}

	public AdvertisementOutbox.Message currentMessage(boolean full) {
		return (full ? extended : legacy).message;
	}

	private static final class Lane {
		private List<byte[]> frames;
		private AdvertisementOutbox.Message message;
		private long createdAt;
		private int index;
	}
}
