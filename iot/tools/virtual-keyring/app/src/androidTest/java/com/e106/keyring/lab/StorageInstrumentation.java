package com.e106.keyring.lab;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.e106.keyring.core.AdvertisementCodec;
import com.e106.keyring.core.AdvertisementFrames;
import com.e106.keyring.core.Encounter;
import com.e106.keyring.core.EncounterEngine;
import com.e106.keyring.core.KeyringMotion;
import com.e106.keyring.core.Packet;
import com.e106.keyring.core.Peer;
import com.e106.keyring.core.SavedEncounter;
import com.e106.keyring.core.SavedEncounter.Phase;

import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.os.SystemClock;

/** Uses its own randomized database; never touches the user's keyring-lab.db. */
public final class StorageInstrumentation extends Instrumentation {
	private boolean uuidProbe;
	private boolean radioFleet;
	private int expectedPeers;
	private String radioGroup;

	@Override
	public void onCreate(Bundle arguments) {
		super.onCreate(arguments);
		uuidProbe = arguments != null && "true".equals(arguments.getString("uuidProbe"));
		radioFleet = arguments != null && "true".equals(arguments.getString("radioFleet"));
		if (radioFleet) {
			expectedPeers = Integer.parseInt(arguments.getString("expectedPeers", "1"));
			radioGroup = arguments.getString("radioGroup");
		}
		start();
	}

	@Override
	public void onStart() {
		if (radioFleet) {
			Bundle result = new Bundle();
			try {
				result.putString("stream", "\nPASS: " + new RadioVerification(getTargetContext())
					.run(expectedPeers, radioGroup) + "\n");
				finish(Activity.RESULT_OK, result);
			} catch (Throwable exception) {
				result.putString("stream", "\nFAIL: " + exception + "\n");
				finish(Activity.RESULT_CANCELED, result);
			}
			return;
		}
		if (uuidProbe) {
			verifyUuidReception();
			return;
		}
		Bundle result = new Bundle();
		String name = "storage-verification-" + UUID.randomUUID() + ".db";
		try {
			verify(name);
			verifyMigration(name + ".v1");
			verifyAutomatic(name + ".auto");
			verifyTraffic(name + ".traffic");
			verifyVersionTwo(name + ".v2");
			verifyVersionThree(name + ".v3");
			ContractStorageVerification.verify(getTargetContext(), name + ".contract");
			LocalStorageVerification.verify(getTargetContext(), name + ".local");
			verifyAura();
			verifyFeedbackLed();
			result.putString("stream", "\nPASS: SQLite reopen, identity, dedup, conflict rollback, "
				+ "failed write, monotonic phase, v1 upgrade preserving evidence, multiple events, "
				+ "automatic record and unfinished recovery, ON-session dedup after DB reopen, "
				+ "v2/v3/v4/v5 to v6 preserving evidence, failed local job recovery and durable feedback claims, "
				+ "message-level dedup and fresh retries, received JSON, visible ON aura, "
				+ "LED bright/dark/bright/idle pixels for the 500ms pattern\n");
			finish(Activity.RESULT_OK, result);
		} catch (Throwable exception) {
			result.putString("stream", "\nFAIL: " + exception + "\n");
			finish(Activity.RESULT_CANCELED, result);
		} finally {
			getTargetContext().deleteDatabase(name);
			getTargetContext().deleteDatabase(name + ".v1");
			getTargetContext().deleteDatabase(name + ".auto");
			getTargetContext().deleteDatabase(name + ".traffic");
			getTargetContext().deleteDatabase(name + ".v2");
			getTargetContext().deleteDatabase(name + ".v3");
		}
	}

	/** An opt-in two-emulator RF probe: the other emulator must be advertising the updated APK. */
	private void verifyUuidReception() {
		Bundle result = new Bundle();
		CountDownLatch received = new CountDownLatch(1);
		AtomicReference<String> failure = new AtomicReference<>();
		ParcelUuid service = new ParcelUuid(AdvertisementCodec.SERVICE);
		BluetoothLeScanner scanner = BleDiagnostics.adapter(getTargetContext()).getBluetoothLeScanner();
		ScanCallback callback = new ScanCallback() {
			@Override
			public void onScanResult(int type, ScanResult scan) {
				if (scan.getScanRecord() == null || scan.getScanRecord().getServiceUuids() == null
					|| !scan.getScanRecord().getServiceUuids().contains(service)) {
					return;
				}
				byte[] frame = scan.getScanRecord().getServiceData(service);
				if (frame != null && frame.length >= 7 && frame[0] == AdvertisementFrames.MARKER) {
					received.countDown();
				}
			}

			@Override
			public void onScanFailed(int code) {
				failure.set("scan failed " + code);
				received.countDown();
			}
		};
		try {
			check(!BleTransport.advertisingParameters().isConnectable(), "GATT connections remain disabled");
			scanner.startScan(Collections.singletonList(BleTransport.scanFilters().get(0)),
				new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
			check(received.await(30, TimeUnit.SECONDS) && failure.get() == null,
				"UUID-only filter must receive encrypted service data via scan response: " + failure.get());
			result.putString("stream", "\nPASS: UUID-only filtered scan received LAB-2 fragments "
				+ "and service UUID from the other emulator; no service-data filter or GATT connection used\n");
			finish(Activity.RESULT_OK, result);
		} catch (Throwable exception) {
			result.putString("stream", "\nFAIL: " + exception + "\n");
			finish(Activity.RESULT_CANCELED, result);
		} finally {
			scanner.stopScan(callback);
		}
	}

	private void verifyTraffic(String name) throws Exception {
		UUID peer = UUID.randomUUID();
		Packet packet = Packet.ping(Packet.PING, UUID.randomUUID());
		try (KeyringDatabase database = new KeyringDatabase(getTargetContext(), name)) {
			for (int index = 0; index < 50; index++) {
				database.recordTraffic("RX", peer, packet, "message-1");
			}
			database.recordTraffic("TX", peer, packet, "message-1");
			check(database.traffic(false, false).size() == 2, "same message repeats collapse; TX and RX are distinct");
			database.recordTraffic("RX", peer, packet, "message-2");
			Peer remote = new Peer(peer, UUID.randomUUID(), 1, true);
			database.recordTraffic("DISCOVERY", peer, Packet.hello(remote), "hello-1");
			Encounter event = new Encounter(UUID.randomUUID(), database.prepare(), remote, 1792602000000L);
			database.save(event, Phase.CONFIRMED);
			database.recordTraffic("SAVED", peer, Packet.event(Packet.STORED, event), "saved:" + event.eventId);
			check(database.traffic(false, false).size() == 5, "fresh receipt, discovery and storage each have a row");
		}
		try (KeyringDatabase reopened = new KeyringDatabase(getTargetContext(), name)) {
			check(reopened.traffic(true, false).size() == 2, "received-only export excludes discovery, TX and storage");
			check(reopened.traffic(false, false).size() == 5, "all categories persist across reopen");
			TrafficEntry received = reopened.traffic(true, false).get(0);
			org.json.JSONObject value = received.toJson();
			check(value.getString("direction").equals("RX") && value.getString("peerDeviceId").equals(peer.toString()),
				"JSON preserves source and direction");
			check(value.getJSONObject("data").getString("token").equals(packet.token.toString()),
				"JSON preserves received data");
			check(value.getString("messageId").equals("message-2"), "JSON distinguishes repeated content");
			android.net.Uri uri = JsonExportProvider.write(getTargetContext(), value.toString());
			try (java.io.InputStream input = getTargetContext().getContentResolver().openInputStream(uri)) {
				check(input != null && input.read() == '{', "shared copy is readable JSON");
			}
			boolean rejected = false;
			try (java.io.OutputStream ignored = getTargetContext().getContentResolver().openOutputStream(uri)) {
				throw new AssertionError("export provider allowed writing");
			} catch (java.io.FileNotFoundException expected) {
				rejected = true;
			}
			check(rejected, "shared copies cannot be written through the provider");
		}
	}

	private void verifyVersionTwo(String name) {
		Peer identity;
		SavedEncounter original;
		try (KeyringDatabase database = new KeyringDatabase(getTargetContext(), name)) {
			identity = database.prepare();
			original = database.save(new Encounter(UUID.randomUUID(), identity,
				new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true), 1792602000000L), Phase.CONFIRMED);
			// Make the exact v2 schema in this isolated test database.
			ContractStorageVerification.restoreLegacySchema(database.getWritableDatabase());
			database.getWritableDatabase().execSQL("DROP TABLE traffic");
			database.getWritableDatabase().setVersion(2);
		}
		try (KeyringDatabase migrated = new KeyringDatabase(getTargetContext(), name)) {
			check(migrated.getReadableDatabase().getVersion() == 6, "v2 upgrades to v6");
			check(migrated.identity(false).deviceId.equals(identity.deviceId), "v2 identity unchanged");
			SavedEncounter retained = migrated.find(original.encounter.eventId);
			check(retained.encounter.sameAs(original.encounter) && retained.submissionId.equals(original.submissionId)
				&& retained.phase == Phase.CONFIRMED, "v2 event, submission and phase unchanged");
			check(migrated.traffic(false, false).isEmpty(), "new traffic table does not invent old received messages");
		}
	}

	private void verifyVersionThree(String name) throws Exception {
		Peer identity;
		SavedEncounter original;
		UUID peer = UUID.randomUUID();
		Packet packet = Packet.ping(Packet.PING, UUID.randomUUID());
		try (KeyringDatabase database = new KeyringDatabase(getTargetContext(), name)) {
			identity = database.prepare();
			original = database.save(new Encounter(UUID.randomUUID(), identity,
				new Peer(peer, UUID.randomUUID(), 1, true), 1792602000000L), Phase.CONFIRMED);
			ContractStorageVerification.restoreLegacySchema(database.getWritableDatabase());
			database.getWritableDatabase().execSQL("DROP TABLE traffic");
			database.getWritableDatabase().execSQL("CREATE TABLE traffic (id INTEGER PRIMARY KEY, "
				+ "direction TEXT NOT NULL, peer_id TEXT NOT NULL, recorded_at INTEGER NOT NULL, "
				+ "payload BLOB NOT NULL, UNIQUE(direction,peer_id,payload))");
			database.getWritableDatabase().execSQL("INSERT INTO traffic VALUES(7,'RX',?,123,?)",
				new Object[] {peer.toString(), packet.encode()});
			database.getWritableDatabase().execSQL("INSERT INTO traffic VALUES(8,'TX',?,456,?)",
				new Object[] {peer.toString(), packet.encode()});
			database.getWritableDatabase().setVersion(3);
		}
		try (KeyringDatabase migrated = new KeyringDatabase(getTargetContext(), name)) {
			check(migrated.getReadableDatabase().getVersion() == 6, "v3 upgrades to v6");
			check(migrated.identity(false).deviceId.equals(identity.deviceId), "v3 identity unchanged");
			SavedEncounter retained = migrated.find(original.encounter.eventId);
			check(retained.encounter.sameAs(original.encounter) && retained.submissionId.equals(original.submissionId)
				&& retained.phase == Phase.CONFIRMED, "v3 encounter evidence unchanged");
			TrafficEntry received = migrated.traffic(true, false).get(0);
			check(received.id == 7 && received.timestamp == 123 && received.packet.token.equals(packet.token)
				&& received.peerId.equals(peer.toString()), "v3 received record preserves ID, time, peer and content");
			TrafficEntry sent = migrated.traffic(false, false).get(0);
			check(sent.id == 8 && sent.timestamp == 456 && sent.categoryName().equals("전송 요청 (이전 기록)"),
				"legacy queue entry is not relabeled as an actual radio transmission");
			check(received.toJson().isNull("messageId"), "legacy receipt has no invented wire message ID");
			migrated.recordTraffic("RX", peer, packet, "new-message-1");
			migrated.recordTraffic("RX", peer, packet, "new-message-2");
			check(migrated.traffic(true, false).size() == 3, "same content after update appends new receipts");
		}
	}

	private void verify(String name) {
		Peer first;
		Encounter event;
		SavedEncounter original;
		try (KeyringDatabase database = new KeyringDatabase(getTargetContext(), name)) {
			check(!database.identity(false).ready, "fresh boot is not ready");
			first = database.prepare();
			Peer second = new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true);
			event = new Encounter(UUID.randomUUID(), first, second, 1792602000000L);
			original = database.save(event, Phase.PREPARED);
			database.save(event, Phase.COMMITTED);
		}
		try (KeyringDatabase database = new KeyringDatabase(getTargetContext(), name)) {
			check(database.identity(false).deviceId.equals(first.deviceId), "identity persists");
			check(!database.identity(false).ready, "restart requires setup for new events");
			Peer reapplied = database.prepare();
			check(reapplied.snapshotId.equals(first.snapshotId), "unchanged lab settings keep snapshot");
			check(database.find(event.eventId).phase == Phase.COMMITTED, "phase persists after reopen");
			check(database.find(event.eventId).submissionId.equals(original.submissionId), "stable submission");
			database.save(event, Phase.CONFIRMED);
			database.save(event, Phase.PREPARED);
			check(database.find(event.eventId).phase == Phase.CONFIRMED, "no phase regression");
			check(database.list().size() == 1, "duplicate does not create another row");
			Peer second = new Peer(event.deviceB, event.snapshotB, event.versionB, true);
			boolean rejected = false;
			try {
				database.save(new Encounter(event.eventId, first, second, event.occurredAt + 1), Phase.CONFIRMED);
			} catch (IllegalArgumentException expected) {
				rejected = true;
			}
			check(rejected && database.find(event.eventId).encounter.sameAs(event), "conflict rollback");
			database.getWritableDatabase().execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON encounters "
				+ "BEGIN SELECT RAISE(ABORT, 'INJECTED_STORAGE_FAILURE'); END");
			rejected = false;
			try {
				database.save(new Encounter(UUID.randomUUID(), first,
					new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true), event.occurredAt), Phase.PREPARED);
			} catch (SQLiteException expected) {
				rejected = true;
			}
			check(rejected && database.list().size() == 1, "write failure remains failure");
		}
	}

	private void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private void verifyMigration(String name) {
		Peer first = new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true);
		Peer second = new Peer(UUID.randomUUID(), UUID.randomUUID(), 2, true);
		Encounter event = new Encounter(UUID.randomUUID(), first, second, 1792602000000L);
		UUID submission = UUID.randomUUID();
		try (SQLiteDatabase old = getTargetContext().openOrCreateDatabase(name, 0, null)) {
			old.execSQL("CREATE TABLE identity (slot INTEGER PRIMARY KEY, device_id TEXT, "
				+ "snapshot_id TEXT, settings_version INTEGER)");
			old.execSQL("INSERT INTO identity VALUES(1,?,?,1)",
				new Object[] {first.deviceId.toString(), first.snapshotId.toString()});
			old.execSQL("CREATE TABLE encounters(event_id TEXT PRIMARY KEY NOT NULL, pair_key TEXT UNIQUE NOT NULL, "
				+ "submission_id TEXT UNIQUE NOT NULL, payload BLOB NOT NULL, "
				+ "phase TEXT NOT NULL, updated_at INTEGER NOT NULL)");
			old.execSQL("INSERT INTO encounters VALUES(?,?,?,?,?,?)", new Object[] {event.eventId.toString(),
				event.pairKey(), submission.toString(), Packet.event(Packet.PROPOSE, event).encode(), "CONFIRMED", 1});
			old.setVersion(1);
		}
		try (KeyringDatabase upgraded = new KeyringDatabase(getTargetContext(), name)) {
			check(upgraded.identity(false).deviceId.equals(first.deviceId), "v1 identity retained");
			SavedEncounter previous = upgraded.find(event.eventId);
			check(previous.encounter.sameAs(event) && previous.submissionId.equals(submission), "v1 evidence retained");
			check(previous.phase == Phase.CONFIRMED, "v1 phase retained");
			Encounter next = new Encounter(UUID.randomUUID(), first, second, event.occurredAt + 1000);
			upgraded.save(next, Phase.PREPARED);
			boolean rejected = false;
			try {
				Encounter blocked = new Encounter(UUID.randomUUID(), first, second, event.occurredAt + 2000);
				upgraded.save(blocked, Phase.PREPARED);
			} catch (IllegalArgumentException expected) {
				rejected = true;
			}
			check(rejected, "one unfinished record per pair");
			upgraded.save(next, Phase.CONFIRMED);
			upgraded.save(event, Phase.PREPARED);
			SavedEncounter latest = upgraded.findPair(first.deviceId, second.deviceId);
			check(upgraded.list().size() == 2 && latest.encounter.sameAs(next),
				"two records; old replay does not change newest record");
		}
	}

	private void verifyAutomatic(String name) {
		Peer second = new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true);
		SavedEncounter original;
		try (KeyringDatabase database = new KeyringDatabase(getTargetContext(), name)) {
			EncounterEngine engine = new EncounterEngine(database.prepare(), database,
				ignored -> { }, ignored -> { }, () -> 1792602000000L);
			engine.connect(true);
			engine.receive(Packet.hello(second));
			engine.onDiscovery(0);
			check(database.list().size() == 1, "discovery creates one durable preparation");
			original = database.list().get(0);
		}
		try (KeyringDatabase database = new KeyringDatabase(getTargetContext(), name)) {
			EncounterEngine engine = new EncounterEngine(database.prepare(), database,
				ignored -> { }, ignored -> { }, () -> 1792603000000L);
			engine.connect(true);
			engine.receive(Packet.hello(second));
			engine.onDiscovery(0);
			check(database.list().size() == 1, "automatic retry after reopen does not insert");
			engine.receive(Packet.event(Packet.READY, original.encounter));
			engine.receive(Packet.event(Packet.STORED, original.encounter));
		}
		try (KeyringDatabase database = new KeyringDatabase(getTargetContext(), name)) {
			EncounterEngine engine = new EncounterEngine(database.prepare(), database,
				ignored -> { }, ignored -> { }, () -> 1792604000000L);
			engine.connect(true, original.encounter.eventId);
			engine.receive(Packet.hello(second));
			for (int index = 0; index < 20; index++) {
				engine.onDiscovery(index * 100000L);
			}
			SavedEncounter retained = database.find(original.encounter.eventId);
			check(database.list().size() == 1 && retained.phase == Phase.CONFIRMED,
				"same ON session suppresses repeat discovery after reopen");
			check(retained.encounter.sameAs(original.encounter) && retained.submissionId.equals(original.submissionId),
				"automatic recovery preserves original payload and submission ID");
			engine.disconnect();
			engine.connect(true);
			engine.receive(Packet.hello(second));
			engine.onDiscovery(0);
			check(database.list().size() == 2, "new ON session adds a record while preserving history");
		}
	}

	private void verifyFeedbackLed() {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		runOnMainSync(() -> {
			try {
				for (boolean dark : new boolean[] {true, false}) {
					KeyringView view = new KeyringView(getTargetContext());
					view.layout(0, 0, 340, 430);
					view.setDark(dark);
					for (long elapsed : new long[] {100, 750, 1100, 1750}) {
						Bitmap bitmap = Bitmap.createBitmap(340, 430, Bitmap.Config.ARGB_8888);
						view.update(true, SystemClock.elapsedRealtime() - elapsed);
						view.draw(new Canvas(bitmap));
						int bright = 0;
						for (int x = 155; x < 185; x++) {
							for (int y = 211; y < 235; y++) {
								int color = bitmap.getPixel(x, y);
								if (Color.red(color) > 240 && Color.green(color) > 190 && Color.blue(color) < 220) {
									bright++;
								}
							}
						}
						check((bright > 8) == KeyringMotion.isPulse(elapsed), "LED pulse/gap pixels at " + elapsed);
						bitmap.recycle();
					}
				}
			} catch (Throwable exception) {
				failure.set(exception);
			}
		});
		if (failure.get() != null) {
			throw new AssertionError("LED feedback verification", failure.get());
		}
	}

	private void verifyAura() {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		runOnMainSync(() -> {
			try {
				for (boolean dark : new boolean[] {true, false}) {
					KeyringView view = new KeyringView(getTargetContext());
					view.setDark(dark);
					view.setBackgroundColor(dark ? Color.BLACK : Color.WHITE);
					view.layout(0, 0, 340, 430);
					Bitmap bitmap = Bitmap.createBitmap(340, 430, Bitmap.Config.ARGB_8888);
					Canvas canvas = new Canvas(bitmap);
					view.draw(canvas);
					int off = bitmap.getPixel(276, 223);
					view.update(true, 0);
					for (int frame = 0; frame < 140; frame++) {
						view.draw(canvas);
					}
					int on = bitmap.getPixel(276, 223);
					check(Color.red(on) - Color.blue(on) > 70, "orange halo remains visible after repeated draws");
					check(dark ? Color.red(on) - Color.red(off) > 80 : Color.blue(off) - Color.blue(on) > 80,
						"ON visibly differs from OFF in both themes");
					view.update(false, 0);
					for (int frame = 0; frame < 140; frame++) {
						view.draw(canvas);
					}
					check(bitmap.getPixel(276, 223) == off, "OFF removes the backlight completely");
					bitmap.recycle();
				}
			} catch (Throwable exception) {
				failure.set(exception);
			}
		});
		if (failure.get() != null) {
			throw new AssertionError("Aura rendering failed: " + failure.get(), failure.get());
		}
	}
}
