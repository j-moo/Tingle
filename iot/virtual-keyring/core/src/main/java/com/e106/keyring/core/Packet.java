package com.e106.keyring.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Private experiment protocol, big endian, strict version and length validation. */
public final class Packet {
	public static final int HELLO = 1;
	public static final int PING = 2;
	public static final int PONG = 3;
	public static final int PROPOSE = 4;
	public static final int READY = 5;
	public static final int COMMIT = 6;
	public static final int STORED = 7;
	public static final int ERROR = 8;
	public static final int NEW_REQUEST = 9;
	public static final int MATCH_DATA = 10;
	public static final int MATCH_RESULT = 11;
	public static final int RECHECK = 12;
	public static final int RECHECK_OK = 13;
	public static final int PREPARE = 14;
	public static final int PREPARED = 15;
	public static final int FINALIZE = 16;
	public static final int DURABLE = 17;
	public static final int RECEIPT = 18;
	public static final int PREPARE_REFERENCE = 19;
	public static final int RECHECK_BUSY = 20;
	public static final int PROFILE_BROADCAST = 21;
	public static final int LOCAL_RECORD = 22;
	public static final int MAX_BYTES = 512;

	public final int type;
	public final Peer peer;
	public final UUID token;
	public final Encounter encounter;
	public final String error;
	public final DemoProfile profile;
	public final UUID context;
	public final boolean allowed;
	public final long occurredAt;
	public final UUID recheckToken;
	public final boolean extended;
	private final boolean capabilities;

	private Packet(int type, Peer peer, UUID token, Encounter encounter, String error) {
		this(type, peer, token, encounter, error, null, null, false);
	}

	private Packet(int type, Peer peer, UUID token, Encounter encounter, String error,
		DemoProfile profile, UUID context, boolean allowed) {
		this(type, peer, token, encounter, error, profile, context, allowed, 0, false, false, null);
	}

	private Packet(int type, Peer peer, UUID token, Encounter encounter, String error,
		DemoProfile profile, UUID context, boolean allowed, long occurredAt, boolean extended, boolean capabilities,
		UUID recheckToken) {
		this.type = type;
		this.peer = peer;
		this.token = token;
		this.encounter = encounter;
		this.error = error;
		this.profile = profile;
		this.context = context;
		this.allowed = allowed;
		this.occurredAt = occurredAt;
		this.recheckToken = recheckToken;
		this.extended = extended;
		this.capabilities = capabilities;
	}

	public static Packet hello(Peer peer, boolean extended) {
		return new Packet(HELLO, peer, null, null, null, null, null, false, 0, extended, true, null);
	}

	public static Packet preparation(Encounter event) {
		return preparation(event.eventId, event.recheckToken, DemoProfile.context(event.profileA, event.profileB),
			event.occurredAt);
	}

	private static Packet preparation(UUID event, UUID recheck, UUID context, long occurredAt) {
		if (occurredAt <= 0) {
			throw new IllegalArgumentException("INVALID_ENCOUNTER_TIME");
		}
		return new Packet(PREPARE_REFERENCE, null, event, null, null, null, context, false,
			occurredAt, false, false, recheck);
	}

	/** Resolve only against authenticated input snapshots, or an already persisted original event. */
	public Encounter resolvePreparation(DemoProfile first, DemoProfile second) {
		if (type != PREPARE_REFERENCE || first == null || second == null
			|| !context.equals(DemoProfile.context(first, second))) {
			throw new IllegalArgumentException("PREPARE_CONTEXT_MISMATCH");
		}
		return first.userId.toString().compareTo(second.userId.toString()) < 0
			? new Encounter(token, first, second, occurredAt, recheckToken)
			: new Encounter(token, second, first, occurredAt, recheckToken);
	}

	public static Packet profile(DemoProfile profile) {
		return new Packet(MATCH_DATA, null, null, null, null, profile, null, false);
	}

	public static Packet broadcastProfile(DemoProfile profile) {
		return new Packet(PROFILE_BROADCAST, null, null, null, null, profile, null, false);
	}

	public static Packet decision(UUID context, boolean allowed) {
		return new Packet(MATCH_RESULT, null, null, null, null, null, context, allowed);
	}

	public static Packet recheck(int type, UUID token, UUID context) {
		return new Packet(type, null, token, null, null, null, context, false);
	}

	public static Packet hello(Peer peer) {
		return new Packet(HELLO, peer, null, null, null);
	}

	public static Packet ping(int type, UUID token) {
		return new Packet(type, null, token, null, null);
	}

	public static Packet event(int type, Encounter encounter) {
		return new Packet(type, null, null, encounter, null);
	}

	public static Packet error(String code) {
		return new Packet(ERROR, null, null, null, code);
	}

	public byte[] encode() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream output = new DataOutputStream(bytes);
			output.writeByte(type == PROFILE_BROADCAST || type == LOCAL_RECORD ? 4
				: capabilities || type == PREPARE_REFERENCE || type == RECHECK_BUSY ? 3
				: type >= MATCH_DATA || (encounter != null && encounter.isContract()) ? 2 : 1);
			output.writeByte(type);
			switch (type) {
				case MATCH_DATA:
				case PROFILE_BROADCAST:
					profile.write(output);
					break;
				case LOCAL_RECORD:
					if (!encounter.independent) {
						throw new IllegalArgumentException("NOT_LOCAL_RECORD");
					}
					writeId(output, encounter.eventId);
					encounter.profileA.write(output);
					encounter.profileB.write(output);
					output.writeLong(encounter.occurredAt);
					break;
				case MATCH_RESULT:
					writeId(output, context);
					output.writeBoolean(allowed);
					break;
				case RECHECK:
				case RECHECK_OK:
				case RECHECK_BUSY:
					writeId(output, token);
					writeId(output, context);
					break;
				case PREPARED:
				case FINALIZE:
				case DURABLE:
				case RECEIPT:
					writeId(output, token);
					break;
				case PREPARE:
					writeContract(output, encounter);
					break;
				case PREPARE_REFERENCE:
					writeId(output, token);
					writeId(output, recheckToken);
					writeId(output, context);
					output.writeLong(occurredAt);
					break;
				case HELLO:
					writePeer(output, peer);
					if (capabilities) {
						output.writeBoolean(extended);
					}
					break;
				case PING:
				case PONG:
				case NEW_REQUEST:
					writeId(output, token);
					break;
				case PROPOSE:
				case READY:
				case COMMIT:
				case STORED:
					if (encounter.isContract()) {
						writeContract(output, encounter);
						break;
					}
					writeId(output, encounter.eventId);
					writePeer(output, new Peer(encounter.deviceA, encounter.snapshotA, encounter.versionA, true));
					writePeer(output, new Peer(encounter.deviceB, encounter.snapshotB, encounter.versionB, true));
					output.writeLong(encounter.occurredAt);
					break;
				case ERROR:
					if (error.length() > 80) {
						throw new IllegalArgumentException("ERROR_TOO_LONG");
					}
					output.writeUTF(error);
					break;
				default:
					throw new IllegalArgumentException("UNKNOWN_MESSAGE");
			}
			if (bytes.size() > MAX_BYTES) {
				throw new IllegalArgumentException("INVALID_MESSAGE_SIZE");
			}
			return bytes.toByteArray();
		} catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
	}

	public static Packet decode(byte[] bytes) {
		if (bytes == null || bytes.length < 2 || bytes.length > MAX_BYTES) {
			throw new IllegalArgumentException("INVALID_MESSAGE_SIZE");
		}
		try {
			DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
			int version = input.readUnsignedByte();
			if (version < 1 || version > 4) {
				throw new IllegalArgumentException("UNSUPPORTED_VERSION");
			}
			int type = input.readUnsignedByte();
			if ((version == 4 && type != PROFILE_BROADCAST && type != LOCAL_RECORD)
				|| ((type == PROFILE_BROADCAST || type == LOCAL_RECORD) && version != 4)
				|| (version == 3 && type != HELLO && type != PREPARE_REFERENCE && type != RECHECK_BUSY)
				|| ((type == PREPARE_REFERENCE || type == RECHECK_BUSY) && version != 3)
				|| (type >= MATCH_DATA && type <= RECEIPT && version != 2)) {
				throw new IllegalArgumentException("UNSUPPORTED_VERSION");
			}
			if (version == 2 && type < MATCH_DATA && (type < PROPOSE || type > STORED)) {
				throw new IllegalArgumentException("UNSUPPORTED_VERSION");
			}
			Packet packet;
			switch (type) {
				case MATCH_DATA:
					packet = profile(DemoProfile.read(input));
					break;
				case PROFILE_BROADCAST:
					packet = broadcastProfile(DemoProfile.read(input));
					break;
				case LOCAL_RECORD:
					packet = event(LOCAL_RECORD, Encounter.independent(readId(input),
						DemoProfile.read(input), DemoProfile.read(input), input.readLong()));
					break;
				case MATCH_RESULT:
					UUID context = readId(input);
					int allowed = input.readUnsignedByte();
					if (allowed > 1) {
						throw new IllegalArgumentException("INVALID_DECISION");
					}
					packet = decision(context, allowed == 1);
					break;
				case RECHECK:
				case RECHECK_OK:
				case RECHECK_BUSY:
					packet = recheck(type, readId(input), readId(input));
					break;
				case PREPARED:
				case FINALIZE:
				case DURABLE:
				case RECEIPT:
					packet = ping(type, readId(input));
					break;
				case PREPARE:
					packet = event(type, readContract(input));
					break;
				case PREPARE_REFERENCE:
					packet = preparation(readId(input), readId(input), readId(input), input.readLong());
					break;
				case HELLO:
					Peer helloPeer = readPeer(input);
					if (version == 3) {
						int flags = input.readUnsignedByte();
						if (flags > 1) {
							throw new IllegalArgumentException("INVALID_CAPABILITIES");
						}
						packet = hello(helloPeer, flags == 1);
					} else {
						packet = hello(helloPeer);
					}
					break;
				case PING:
				case PONG:
				case NEW_REQUEST:
					packet = ping(type, readId(input));
					break;
				case PROPOSE:
				case READY:
				case COMMIT:
				case STORED:
					if (version == 2) {
						packet = event(type, readContract(input));
						break;
					}
					Encounter encounter = new Encounter(readId(input), readPeer(input), readPeer(input),
						input.readLong());
					packet = event(type, encounter);
					break;
				case ERROR:
					String error = input.readUTF();
					if (!error.matches("[A-Z0-9_]{1,80}")) {
						throw new IllegalArgumentException("INVALID_ERROR_CODE");
					}
					packet = error(error);
					break;
				default:
					throw new IllegalArgumentException("UNKNOWN_MESSAGE");
			}
			if (input.available() != 0) {
				throw new IllegalArgumentException("TRAILING_BYTES");
			}
			return packet;
		} catch (IOException exception) {
			throw new IllegalArgumentException("TRUNCATED_MESSAGE", exception);
		}
	}

	private static void writeContract(DataOutputStream output, Encounter encounter) throws IOException {
		if (!encounter.isContract()) {
			throw new IllegalArgumentException("CONTRACT_EVIDENCE_REQUIRED");
		}
		writeId(output, encounter.eventId);
		encounter.profileA.write(output);
		encounter.profileB.write(output);
		output.writeLong(encounter.occurredAt);
		writeId(output, encounter.recheckToken);
	}

	private static Encounter readContract(DataInputStream input) throws IOException {
		return new Encounter(readId(input), DemoProfile.read(input), DemoProfile.read(input),
			input.readLong(), readId(input));
	}

	static void writePeer(DataOutputStream output, Peer peer) throws IOException {
		writeId(output, peer.deviceId);
		writeId(output, peer.snapshotId);
		output.writeLong(peer.settingsVersion);
		output.writeByte(peer.ready ? 1 : 0);
	}

	static Peer readPeer(DataInputStream input) throws IOException {
		UUID deviceId = readId(input);
		UUID snapshotId = readId(input);
		long version = input.readLong();
		int ready = input.readUnsignedByte();
		if (ready > 1) {
			throw new IllegalArgumentException("INVALID_READY_FLAG");
		}
		return new Peer(deviceId, snapshotId, version, ready == 1);
	}

	static void writeId(DataOutputStream output, UUID value) throws IOException {
		output.writeLong(value.getMostSignificantBits());
		output.writeLong(value.getLeastSignificantBits());
	}

	static UUID readId(DataInputStream input) throws IOException {
		return new UUID(input.readLong(), input.readLong());
	}
}
