package com.e106.keyring.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.e106.keyring.core.DemoProfile;
import com.e106.keyring.core.Encounter;
import com.e106.keyring.core.FlowEvent;
import com.e106.keyring.core.LocalEvidence;
import com.e106.keyring.core.LocalMatchStore;
import com.e106.keyring.core.MatchWork;
import com.e106.keyring.core.Packet;
import com.e106.keyring.core.Peer;
import com.e106.keyring.core.SavedEncounter;
import com.e106.keyring.core.SavedEncounter.Phase;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** App-private storage. Never silently reset records after a migration or a write failure. */
public final class KeyringDatabase extends SQLiteOpenHelper implements LocalMatchStore {
	public KeyringDatabase(Context context, String name) {
		super(context, name, null, 6);
	}

	@Override
	public void onConfigure(SQLiteDatabase database) {
		database.execSQL("PRAGMA synchronous=FULL");
	}

	@Override
	public void onCreate(SQLiteDatabase database) {
		database.execSQL("CREATE TABLE identity (slot INTEGER PRIMARY KEY CHECK(slot=1), "
			+ "device_id TEXT NOT NULL, snapshot_id TEXT NOT NULL, settings_version INTEGER NOT NULL)");
		createEncounters(database);
		createTraffic(database);
		createContractStorage(database);
		createWorkStorage(database);
		ContentValues identity = new ContentValues();
		identity.put("slot", 1);
		identity.put("device_id", UUID.randomUUID().toString());
		identity.put("snapshot_id", UUID.randomUUID().toString());
		identity.put("settings_version", 0);
		database.insertOrThrow("identity", null, identity);
	}

	@Override
	public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
		if (oldVersion < 1 || oldVersion > 5 || newVersion != 6) {
			throw new IllegalStateException("DB_MIGRATION_REQUIRED");
		}
		if (oldVersion == 5) {
			createWorkStorage(database);
			return;
		}
		// SQLiteOpenHelper runs the complete copy in one transaction, including rollback on failure.
		if (oldVersion == 1) {
			database.execSQL("ALTER TABLE encounters RENAME TO encounters_v1");
			createEncounters(database);
			database.execSQL("INSERT INTO encounters(rowid,event_id,pair_key,submission_id,payload,phase,updated_at) "
				+ "SELECT rowid,event_id,pair_key,submission_id,payload,phase,updated_at FROM encounters_v1");
			database.execSQL("DROP TABLE encounters_v1");
		}
		if (oldVersion == 3 || oldVersion == 4) {
			database.execSQL("ALTER TABLE traffic RENAME TO traffic_v3");
		}
		createTraffic(database);
		if (oldVersion == 3 || oldVersion == 4) {
			database.execSQL("INSERT INTO traffic(id,direction,peer_id,recorded_at,payload,message_key) "
				+ "SELECT id,direction,peer_id,recorded_at,payload,"
				+ (oldVersion == 3 ? "'legacy:' || id" : "message_key") + " FROM traffic_v3");
			database.execSQL("DROP TABLE traffic_v3");
		}
		createContractStorage(database);
		createWorkStorage(database);
	}

	private void createWorkStorage(SQLiteDatabase database) {
		database.execSQL("CREATE TABLE processing_jobs(job_id TEXT PRIMARY KEY NOT NULL, payload BLOB NOT NULL)");
	}

	@Override
	public List<MatchWork> pendingWork() {
		List<MatchWork> jobs = new ArrayList<>();
		try (Cursor cursor = getReadableDatabase().rawQuery(
			"SELECT payload FROM processing_jobs ORDER BY rowid", null)) {
			while (cursor.moveToNext()) {
				jobs.add(MatchWork.decode(cursor.getBlob(0)));
			}
		}
		return jobs;
	}

	@Override
	public void putWork(MatchWork work) {
		ContentValues values = new ContentValues();
		values.put("job_id", work.id.toString());
		values.put("payload", work.encode());
		if (getWritableDatabase().insertWithOnConflict("processing_jobs", null, values,
			SQLiteDatabase.CONFLICT_REPLACE) == -1) {
			throw new IllegalStateException("JOB_WRITE_FAILED");
		}
	}

	@Override
	public void removeWork(UUID id) {
		getWritableDatabase().delete("processing_jobs", "job_id=?", new String[] {id.toString()});
	}

	private void createContractStorage(SQLiteDatabase database) {
		database.execSQL("ALTER TABLE encounters ADD COLUMN contract INTEGER NOT NULL DEFAULT 0");
		database.execSQL("DROP INDEX one_pending_per_pair");
		database.execSQL("CREATE UNIQUE INDEX one_pending_per_pair ON encounters(pair_key,contract) "
			+ "WHERE phase != 'CONFIRMED'");
		database.execSQL("CREATE TABLE lab_fixture(slot INTEGER PRIMARY KEY CHECK(slot=1), payload BLOB NOT NULL)");
		database.execSQL("CREATE TABLE contract_state(event_id TEXT PRIMARY KEY NOT NULL, device_id TEXT NOT NULL, "
			+ "snapshot_id TEXT NOT NULL, peer_credential TEXT NOT NULL, evidence TEXT NOT NULL, "
			+ "feedback TEXT NOT NULL DEFAULT '')");
	}

	public DemoProfile fixture() {
		SQLiteDatabase database = getWritableDatabase();
		try (Cursor cursor = database.rawQuery("SELECT payload FROM lab_fixture WHERE slot=1", null)) {
			if (cursor.moveToFirst()) {
				return Packet.decode(cursor.getBlob(0)).profile;
			}
		}
		DemoProfile profile = DemoProfile.fixture(prepare());
		database.execSQL("INSERT INTO lab_fixture(slot,payload) VALUES(1,?)",
			new Object[] {Packet.profile(profile).encode()});
		return profile;
	}

	private void createTraffic(SQLiteDatabase database) {
		database.execSQL("CREATE TABLE traffic (id INTEGER PRIMARY KEY, direction TEXT NOT NULL, "
			+ "peer_id TEXT NOT NULL, recorded_at INTEGER NOT NULL, payload BLOB, "
			+ "message_key TEXT NOT NULL, code TEXT NOT NULL DEFAULT '', detail TEXT NOT NULL DEFAULT '', "
			+ "event_id TEXT, UNIQUE(direction,peer_id,message_key))");
	}

	public void recordProcess(FlowEvent event) {
		ContentValues values = new ContentValues();
		values.put("direction", "PROCESS");
		values.put("peer_id", event.peer == null ? new UUID(0, 0).toString() : event.peer.toString());
		values.put("recorded_at", System.currentTimeMillis());
		values.put("message_key", UUID.randomUUID().toString());
		values.put("code", event.code);
		values.put("detail", event.detail);
		if (event.eventId != null) {
			values.put("event_id", event.eventId.toString());
		}
		getWritableDatabase().insertOrThrow("traffic", null, values);
	}

	public void recordTraffic(String direction, UUID peer, Packet packet, String messageKey) {
		// Same encrypted message's fragments collapse; a fresh send remains visible even with identical content.
		getWritableDatabase().execSQL("INSERT OR IGNORE INTO traffic"
			+ "(direction,peer_id,recorded_at,payload,message_key) VALUES(?,?,?,?,?)",
			new Object[] {direction, peer.toString(), System.currentTimeMillis(), packet.encode(), messageKey});
	}

	public List<TrafficEntry> traffic(boolean receivedOnly, boolean recentOnly) {
		return traffic(receivedOnly, recentOnly ? 80 : 0);
	}

	public long trafficCount() {
		try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM traffic", null)) {
			cursor.moveToFirst();
			return cursor.getLong(0);
		}
	}

	public List<TrafficEntry> traffic(boolean receivedOnly, int limit) {
		List<TrafficEntry> entries = new ArrayList<>();
		String rowLimit = limit == 0 ? null : Integer.toString(limit);
		try (Cursor cursor = getReadableDatabase().query("traffic", null,
			receivedOnly ? "direction='RX'" : null, null, null, null, "id DESC", rowLimit)) {
			while (cursor.moveToNext()) {
				entries.add(new TrafficEntry(cursor.getLong(cursor.getColumnIndexOrThrow("id")),
					cursor.getLong(cursor.getColumnIndexOrThrow("recorded_at")),
					cursor.getString(cursor.getColumnIndexOrThrow("direction")),
					cursor.getString(cursor.getColumnIndexOrThrow("peer_id")),
					cursor.isNull(cursor.getColumnIndexOrThrow("payload")) ? null
					: Packet.decode(cursor.getBlob(cursor.getColumnIndexOrThrow("payload"))),
					cursor.getString(cursor.getColumnIndexOrThrow("message_key")),
					cursor.getString(cursor.getColumnIndexOrThrow("code")),
					cursor.getString(cursor.getColumnIndexOrThrow("detail")),
					cursor.getString(cursor.getColumnIndexOrThrow("event_id"))));
			}
		}
		return entries;
	}

	private void createEncounters(SQLiteDatabase database) {
		database.execSQL("CREATE TABLE encounters (event_id TEXT PRIMARY KEY NOT NULL, "
			+ "pair_key TEXT NOT NULL, submission_id TEXT UNIQUE NOT NULL, "
			+ "payload BLOB NOT NULL, phase TEXT NOT NULL, updated_at INTEGER NOT NULL)");
		database.execSQL("CREATE UNIQUE INDEX one_pending_per_pair ON encounters(pair_key) "
			+ "WHERE phase != 'CONFIRMED'");
	}

	public Peer identity(boolean ready) {
		try (Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM identity WHERE slot=1", null)) {
			if (!cursor.moveToFirst()) {
				throw new IllegalStateException("IDENTITY_MISSING");
			}
			return new Peer(UUID.fromString(cursor.getString(cursor.getColumnIndexOrThrow("device_id"))),
				UUID.fromString(cursor.getString(cursor.getColumnIndexOrThrow("snapshot_id"))),
				cursor.getLong(cursor.getColumnIndexOrThrow("settings_version")), ready);
		}
	}

	public Peer prepare() {
		SQLiteDatabase database = getWritableDatabase();
		database.beginTransaction();
		try {
			Peer old = identity(false);
			ContentValues values = new ContentValues();
			// The lab fixture has no editable settings. Reapplying after boot keeps the same snapshot.
			values.put("snapshot_id", old.snapshotId.toString());
			values.put("settings_version", Math.max(1, old.settingsVersion));
			if (database.update("identity", values, "slot=1", null) != 1) {
				throw new IllegalStateException("SETTINGS_WRITE_FAILED");
			}
			database.setTransactionSuccessful();
		} finally {
			database.endTransaction();
		}
		return identity(true);
	}

	@Override
	public SavedEncounter find(UUID eventId) {
		return queryOne("event_id=?", new String[] {eventId.toString()});
	}

	@Override
	public SavedEncounter findPair(UUID first, UUID second) {
		String left = first.toString();
		String right = second.toString();
		String pair = left.compareTo(right) < 0 ? left + ":" + right : right + ":" + left;
		return queryOne("pair_key=?", new String[] {pair});
	}

	private SavedEncounter queryOne(String selection, String[] args) {
		try (Cursor cursor = getReadableDatabase().query("encounters", null, selection, args,
			null, null, "rowid DESC", "1")) {
			return cursor.moveToFirst() ? read(cursor) : null;
		}
	}

	@Override
	public SavedEncounter save(Encounter encounter, Phase phase) {
		SQLiteDatabase database = getWritableDatabase();
		database.beginTransaction();
		SavedEncounter result;
		try {
			SavedEncounter old = find(encounter.eventId);
			if (old != null && !old.encounter.sameAs(encounter)) {
				throw new IllegalArgumentException("EVENT_CONFLICT");
			}
			if (old == null) {
				if (list().stream().anyMatch(value -> value.phase != Phase.CONFIRMED
					&& value.encounter.isContract() == encounter.isContract()
					&& value.encounter.independent == encounter.independent
					&& value.encounter.pairKey().equals(encounter.pairKey()))) {
					throw new IllegalArgumentException("PAIR_HAS_PENDING_ENCOUNTER");
				}
				try (Cursor cursor = database.rawQuery("SELECT COUNT(*) FROM encounters", null)) {
					cursor.moveToFirst();
					if (cursor.getInt(0) >= 256) {
						throw new IllegalStateException("LAB_STORAGE_FULL");
					}
				}
			}
			result = new SavedEncounter(encounter, old == null ? UUID.randomUUID() : old.submissionId,
				old == null ? phase : SavedEncounter.advance(old.phase, phase));
			LocalEvidence evidence = old == null ? null : old.evidence;
			if (encounter.hasProfiles() && old == null) {
				evidence = LocalEvidence.create(encounter, identity(false).deviceId, result.submissionId);
				database.execSQL("INSERT INTO contract_state(event_id,device_id,snapshot_id,peer_credential,evidence) "
					+ "VALUES(?,?,?,?,?)", new Object[] {encounter.eventId.toString(), evidence.deviceId.toString(),
						evidence.snapshotId.toString(), evidence.peerCredential, evidence.payload});
			}
			if (encounter.hasProfiles() && evidence == null) {
				throw new IllegalStateException("ORIGINAL_EVIDENCE_MISSING");
			}
			result = new SavedEncounter(encounter, result.submissionId, result.phase, evidence);
			ContentValues values = new ContentValues();
			values.put("event_id", encounter.eventId.toString());
			values.put("pair_key", encounter.pairKey());
			values.put("submission_id", result.submissionId.toString());
			values.put("payload",
				Packet.event(encounter.independent ? Packet.LOCAL_RECORD : Packet.PROPOSE, encounter).encode());
			values.put("phase", result.phase.name());
			values.put("contract", encounter.independent ? 2 : encounter.isContract() ? 1 : 0);
			values.put("updated_at", System.currentTimeMillis());
			if (old == null) {
				database.insertOrThrow("encounters", null, values);
			} else if (database.update("encounters", values, "event_id=?",
				new String[] {encounter.eventId.toString()}) != 1) {
				throw new IllegalStateException("RECORD_WRITE_FAILED");
			}
			database.setTransactionSuccessful();
		} finally {
			database.endTransaction();
		}
		return result;
	}

	@Override
	public List<SavedEncounter> list() {
		List<SavedEncounter> records = new ArrayList<>();
		try (Cursor cursor = getReadableDatabase().query("encounters", null, null, null,
			null, null, "rowid DESC")) {
			while (cursor.moveToNext()) {
				records.add(read(cursor));
			}
		}
		return records;
	}

	private SavedEncounter read(Cursor cursor) {
		Packet packet = Packet.decode(cursor.getBlob(cursor.getColumnIndexOrThrow("payload")));
		LocalEvidence evidence = null;
		if (packet.encounter.hasProfiles()) {
			try (Cursor proof = getReadableDatabase().rawQuery("SELECT * FROM contract_state WHERE event_id=?",
				new String[] {packet.encounter.eventId.toString()})) {
				if (!proof.moveToFirst()) {
					throw new IllegalStateException("ORIGINAL_EVIDENCE_MISSING");
				}
				evidence = new LocalEvidence(UUID.fromString(proof.getString(proof.getColumnIndexOrThrow("device_id"))),
					UUID.fromString(proof.getString(proof.getColumnIndexOrThrow("snapshot_id"))),
					proof.getString(proof.getColumnIndexOrThrow("peer_credential")),
					proof.getString(proof.getColumnIndexOrThrow("evidence")));
			}
		}
		return new SavedEncounter(packet.encounter,
			UUID.fromString(cursor.getString(cursor.getColumnIndexOrThrow("submission_id"))),
			Phase.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("phase"))), evidence);
	}

	@Override
	public boolean claimFeedback(UUID eventId, String state) {
		ContentValues values = new ContentValues();
		values.put("feedback", state);
		return getWritableDatabase().update("contract_state", values, "event_id=? AND feedback=''",
			new String[] {eventId.toString()}) == 1;
	}

	@Override
	public void finishFeedback(UUID eventId, String state) {
		ContentValues values = new ContentValues();
		values.put("feedback", state);
		getWritableDatabase().update("contract_state", values, "event_id=? AND feedback IN ('CLAIMED','STARTED')",
			new String[] {eventId.toString()});
	}

	@Override
	public String feedbackState(UUID eventId) {
		try (Cursor cursor = getReadableDatabase().rawQuery("SELECT feedback FROM contract_state WHERE event_id=?",
			new String[] {eventId.toString()})) {
			return cursor.moveToFirst() ? cursor.getString(0) : "";
		}
	}
}
