package com.e106.keyring.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Persisted input and progress of one local job. Confined to the processing worker. */
public final class MatchWork {
	public enum Stage { MATCH, SAVE, FEEDBACK, DONE }

	public final UUID id;
	public final UUID eventId;
	public final DemoProfile local;
	public final DemoProfile remote;
	public final long receivedAt;
	public Stage stage = Stage.MATCH;
	public long matchedAt;
	public int attempts;
	public long retryAt;
	public String error = "";

	public MatchWork(DemoProfile local, DemoProfile remote, long receivedAt) {
		this(UUID.randomUUID(), local, remote, receivedAt);
	}

	private MatchWork(UUID eventId, DemoProfile local, DemoProfile remote, long receivedAt) {
		id = DemoProfile.context(local, remote);
		this.eventId = eventId;
		this.local = local;
		this.remote = remote;
		this.receivedAt = receivedAt;
	}

	public byte[] encode() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream output = new DataOutputStream(bytes);
			output.writeByte(1);
			Packet.writeId(output, eventId);
			local.write(output);
			remote.write(output);
			output.writeLong(receivedAt);
			output.writeUTF(stage.name());
			output.writeLong(matchedAt);
			output.writeInt(attempts);
			output.writeLong(retryAt);
			output.writeUTF(error);
			return bytes.toByteArray();
		} catch (IOException exception) {
			throw new IllegalStateException("JOB_ENCODING_FAILED", exception);
		}
	}

	public static MatchWork decode(byte[] bytes) {
		try {
			DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
			if (input.readUnsignedByte() != 1) {
				throw new IllegalArgumentException("UNKNOWN_JOB_VERSION");
			}
			MatchWork work = new MatchWork(Packet.readId(input), DemoProfile.read(input), DemoProfile.read(input),
				input.readLong());
			work.stage = Stage.valueOf(input.readUTF());
			work.matchedAt = input.readLong();
			work.attempts = input.readInt();
			work.retryAt = input.readLong();
			work.error = input.readUTF();
			if (input.available() != 0 || work.attempts < 0) {
				throw new IllegalArgumentException("INVALID_JOB");
			}
			return work;
		} catch (IOException exception) {
			throw new IllegalArgumentException("INVALID_JOB", exception);
		}
	}
}
