package com.e106.keyring.lab;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.UUID;

import com.e106.keyring.core.CandidateQueue;
import com.e106.keyring.core.ContractEngine;
import com.e106.keyring.core.DemoProfile;
import com.e106.keyring.core.Encounter;
import com.e106.keyring.core.MatchingRule;
import com.e106.keyring.core.Packet;
import com.e106.keyring.core.Peer;
import com.e106.keyring.core.SavedEncounter;
import com.e106.keyring.core.SavedEncounter.Phase;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

/** Isolated test databases only. Never reads, clears or rewrites the user's keyring-lab.db. */
public final class ContractStorageVerification {
	private ContractStorageVerification() {
	}

	public static void verify(Context context, String name) throws Exception {
		try {
			migration(context, name + ".v4");
			flow(context, name + ".left", name + ".right");
		} finally {
			context.deleteDatabase(name + ".v4");
			context.deleteDatabase(name + ".left");
			context.deleteDatabase(name + ".right");
		}
	}

	static void restoreLegacySchema(SQLiteDatabase database) {
		database.execSQL("DROP INDEX one_pending_per_pair");
		database.execSQL("ALTER TABLE encounters RENAME TO encounters_v5_test");
		database.execSQL("CREATE TABLE encounters(event_id TEXT PRIMARY KEY NOT NULL, pair_key TEXT NOT NULL, "
			+ "submission_id TEXT UNIQUE NOT NULL, payload BLOB NOT NULL, phase TEXT NOT NULL, "
			+ "updated_at INTEGER NOT NULL)");
		database.execSQL("INSERT INTO encounters SELECT event_id,pair_key,submission_id,payload,phase,updated_at "
			+ "FROM encounters_v5_test");
		database.execSQL("DROP TABLE encounters_v5_test");
		database.execSQL("CREATE UNIQUE INDEX one_pending_per_pair ON encounters(pair_key) WHERE phase != 'CONFIRMED'");
		database.execSQL("DROP TABLE contract_state");
		database.execSQL("DROP TABLE lab_fixture");
		database.execSQL("DROP TABLE processing_jobs");
	}

	private static void migration(Context context, String name) throws Exception {
		SavedEncounter original;
		byte[] oldPayload;
		Peer local;
		Peer remote = new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true);
		try (KeyringDatabase database = new KeyringDatabase(context, name)) {
			local = database.prepare();
			original = database.save(new Encounter(UUID.randomUUID(), local, remote, 1800000000000L), Phase.PREPARED);
			database.recordTraffic("RX", remote.deviceId, Packet.hello(remote), "original-received-message");
			oldPayload = payload(database, original.encounter.eventId);
			SQLiteDatabase sql = database.getWritableDatabase();
			restoreLegacySchema(sql);
			sql.execSQL("ALTER TABLE traffic RENAME TO traffic_v5_test");
			sql.execSQL("CREATE TABLE traffic(id INTEGER PRIMARY KEY,direction TEXT NOT NULL,peer_id TEXT NOT NULL,"
				+ "recorded_at INTEGER NOT NULL,payload BLOB NOT NULL,message_key TEXT NOT NULL,"
				+ "UNIQUE(direction,peer_id,message_key))");
			sql.execSQL("INSERT INTO traffic SELECT id,direction,peer_id,recorded_at,payload,message_key "
				+ "FROM traffic_v5_test");
			sql.execSQL("DROP TABLE traffic_v5_test");
			sql.setVersion(4);
		}
		try (KeyringDatabase migrated = new KeyringDatabase(context, name)) {
			check(migrated.getReadableDatabase().getVersion() == 6, "v4 upgrades to v6");
			SavedEncounter retained = migrated.find(original.encounter.eventId);
			check(retained.phase == original.phase && retained.submissionId.equals(original.submissionId)
				&& Arrays.equals(oldPayload, payload(migrated, original.encounter.eventId)),
				"v4 payload byte preservation");
			check(retained.evidence == null, "legacy proof is not manufactured");
			check(migrated.traffic(false, false).size() == 1
				&& migrated.traffic(true, false).get(0).messageKey.equals("original-received-message"),
				"v4 traffic retained");
			DemoProfile own = migrated.fixture();
			DemoProfile other = DemoProfile.fixture(remote);
			boolean first = own.userId.toString().compareTo(other.userId.toString()) < 0;
			Encounter event = new Encounter(UUID.randomUUID(), first ? own : other, first ? other : own,
				1800000000000L, UUID.randomUUID());
			migrated.save(event, Phase.PREPARED);
			check(migrated.list().size() == 2,
				"new contract flow does not rewrite or resume incomplete legacy experiment");
			check(migrated.identity(false).deviceId.equals(local.deviceId), "device identity retained");
		}
	}

	private static void flow(Context context, String leftName, String rightName) throws Exception {
		SavedEncounter frozen;
		byte[] fixture;
		try (KeyringDatabase left = new KeyringDatabase(context, leftName);
			KeyringDatabase right = new KeyringDatabase(context, rightName)) {
			DemoProfile first = left.fixture();
			DemoProfile second = right.fixture();
			fixture = Packet.profile(first).encode();
			Queue<Packet> leftToRight = new ArrayDeque<>();
			Queue<Packet> rightToLeft = new ArrayDeque<>();
			long[] now = {1800000000000L};
			ContractEngine leftEngine = new ContractEngine(first, second.keyring.deviceId, left, new CandidateQueue(),
				MatchingRule.alwaysMatch(), leftToRight::add, left::recordProcess, () -> now[0], () -> now[0]);
			ContractEngine rightEngine = new ContractEngine(second, first.keyring.deviceId, right, new CandidateQueue(),
				MatchingRule.alwaysMatch(), rightToLeft::add, right::recordProcess, () -> now[0], () -> now[0]);
			leftEngine.start();
			rightEngine.start();
			for (int step = 0; step < 80; step++) {
				while (!leftToRight.isEmpty()) {
					rightEngine.receive(Packet.decode(leftToRight.remove().encode()));
				}
				while (!rightToLeft.isEmpty()) {
					leftEngine.receive(Packet.decode(rightToLeft.remove().encode()));
				}
				leftEngine.tick();
				rightEngine.tick();
				now[0] += 1000;
			}
			check(left.list().size() == 1 && right.list().size() == 1, "one event persisted on both databases");
			frozen = left.list().get(0);
			check(frozen.phase == Phase.CONFIRMED && right.list().get(0).phase == Phase.CONFIRMED,
				"both real SQLite transactions confirmed");
			check(frozen.encounter.sameAs(right.list().get(0).encounter), "shared immutable event equal");
			org.json.JSONObject submission = ContractJson.submission(frozen);
			check(submission.length() == 8 && submission.getJSONObject("evidence").length() == 2,
				"submission preview contains only the eight contract fields and two proof fields");
			check(submission.getString("settingsSnapshotId").equals(first.keyring.snapshotId.toString()),
				"submission exports own snapshot, not peer/current settings");
			check(!frozen.evidence.payload.equals(right.list().get(0).evidence.payload), "distinct local proof saved");
			check(left.feedbackState(frozen.encounter.eventId).equals("CLAIMED"), "feedback claim durable");
			left.finishFeedback(frozen.encounter.eventId, "STOPPED");
			for (TrafficEntry entry : left.traffic(false, false)) {
				check(entry.toJson().has("data"), "every process entry exports JSON");
			}
			check(left.traffic(false, false).stream().anyMatch(entry -> entry.code.equals("UPLOAD_PENDING")),
				"upload queue logged without a fake server receipt");
			long before = proofCount(left);
			left.getWritableDatabase().execSQL("CREATE TRIGGER fail_contract_insert BEFORE INSERT ON encounters "
				+ "BEGIN SELECT RAISE(ABORT,'TEST_WRITE_FAILURE'); END");
			Encounter extra = new Encounter(UUID.randomUUID(), frozen.encounter.profileA, frozen.encounter.profileB,
				frozen.encounter.occurredAt + 1, UUID.randomUUID());
			boolean rejected = false;
			try {
				left.save(extra, Phase.PREPARED);
			} catch (SQLiteException expected) {
				rejected = true;
			}
			check(rejected && proofCount(left) == before && left.find(extra.eventId) == null,
				"record and local evidence roll back atomically");
		}
		try (KeyringDatabase reopened = new KeyringDatabase(context, leftName)) {
			SavedEncounter retained = reopened.find(frozen.encounter.eventId);
			check(Arrays.equals(fixture, Packet.profile(reopened.fixture()).encode()), "synthetic profile persists");
			check(retained.submissionId.equals(frozen.submissionId)
				&& retained.evidence.payload.equals(frozen.evidence.payload),
				"original submission and proof survive reopen");
			check(reopened.feedbackState(frozen.encounter.eventId).equals("STOPPED")
				&& !reopened.claimFeedback(frozen.encounter.eventId, "CLAIMED"),
				"manual stop never replays after restart");
		}
	}

	private static long proofCount(KeyringDatabase database) {
		try (Cursor cursor = database.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM contract_state", null)) {
			cursor.moveToFirst();
			return cursor.getLong(0);
		}
	}

	private static byte[] payload(KeyringDatabase database, UUID id) {
		try (Cursor cursor = database.getReadableDatabase().rawQuery("SELECT payload FROM encounters WHERE event_id=?",
			new String[] {id.toString()})) {
			cursor.moveToFirst();
			return cursor.getBlob(0);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
