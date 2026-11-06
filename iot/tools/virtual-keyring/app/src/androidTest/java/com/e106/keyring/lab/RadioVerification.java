package com.e106.keyring.lab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;

import com.e106.keyring.core.AdvertisementCodec;
import com.e106.keyring.core.AdvertisementOutbox;
import com.e106.keyring.core.DemoProfile;
import com.e106.keyring.core.FlowEvent;
import com.e106.keyring.core.IndependentMatcher;
import com.e106.keyring.core.MatchingRule;
import com.e106.keyring.core.Packet;
import com.e106.keyring.core.SavedEncounter;
import com.e106.keyring.core.SavedEncounter.Phase;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

/** Opt-in RF integration test. Uses a unique test database and an explicitly supplied isolated group. */
final class RadioVerification implements BleTransport.Listener {
	private final Context context;
	private IndependentMatcher matcher;
	private final List<String> timings = new ArrayList<>();
	private final Map<String, Integer> stages = new LinkedHashMap<>();
	private final List<String> statuses = new ArrayList<>();
	private final CountDownLatch finished = new CountDownLatch(1);
	private final AtomicReference<Throwable> failure = new AtomicReference<>();
	private KeyringDatabase database;
	private DemoProfile local;
	private Handler handler;
	private BleTransport radio;
	private long completedAt;
	private long startedAt;
	private int peers;

	RadioVerification(Context context) {
		this.context = context;
	}

	String run(int expectedPeers, String group) throws Exception {
		check(expectedPeers >= 1 && expectedPeers <= 15 && group != null, "explicit isolated fleet parameters");
		check(!BleTransport.scanSettings(true).getLegacy(), "extended results included by scanner");
		check(!BleTransport.advertisingParameters(true).isLegacy()
			&& !BleTransport.advertisingParameters(true).isScannable()
			&& !BleTransport.advertisingParameters(true).isConnectable(), "non-connectable extended advertisement");
		check(BleTransport.advertisingParameters(true).getSecondaryPhy() == BluetoothDevice.PHY_LE_1M,
			"secondary PHY remains 1M for compatibility");
		peers = expectedPeers;
		String name = "radio-verification-" + UUID.randomUUID() + ".db";
		HandlerThread thread = new HandlerThread("radio-verification");
		thread.start();
		handler = new Handler(thread.getLooper());
		try {
			database = new KeyringDatabase(context, name);
			local = database.fixture();
			radio = new BleTransport(context, handler, this);
			startedAt = SystemClock.elapsedRealtime();
			handler.post(() -> {
				try {
					radio.start(local, group);
					matcher = new IndependentMatcher(local, database, MatchingRule.alwaysMatch(),
						System::currentTimeMillis, this::process);
					matcher.restore();
					tick();
				} catch (Throwable exception) {
					failed(exception);
				}
			});
			check(finished.await(120, TimeUnit.SECONDS), "RF timeout; " + radio.counters() + "; stages=" + stages);
			check(failure.get() == null, "RF failure: " + failure.get());
			CountDownLatch stopped = new CountDownLatch(1);
			handler.post(() -> {
				radio.close();
				stopped.countDown();
			});
			check(stopped.await(5, TimeUnit.SECONDS), "radio cleanup");
			check(statuses.stream().anyMatch(value -> value.startsWith("EXTENDED_STARTED")), "extended start callback");
			check(statuses.stream().anyMatch(value -> value.contains("EXTENDED_SINGLE; type=21; ad_bytes=216")),
				"complete PROFILE_BROADCAST accepted by the Android controller within 255 bytes");
			check(statuses.stream().noneMatch(value -> value.startsWith("EXTENDED_FALLBACK")),
				"supported emulator used the extended path without fallback");
			check(matcher.errorCount() == 0 && database.pendingWork().isEmpty(), "no unfinished/error work");
			List<SavedEncounter> original = database.list();
			check(original.size() == peers, "no duplicate pair events");
			long received = database.traffic(true, false).stream().filter(entry -> entry.packet != null
				&& entry.packet.type == Packet.PROFILE_BROADCAST).count();
			check(received >= peers, "peer full profile received over the radio");
			for (TrafficEntry entry : database.traffic(false, false)) {
				check(entry.toJson().has("data"), "every process and compact packet exports JSON");
			}
			JSONObject report = new JSONObject();
			report.put("device", local.keyring.deviceId);
			report.put("radio_to_all_saved_ms", completedAt - startedAt);
			report.put("dedup_hold_ms", SystemClock.elapsedRealtime() - completedAt);
			report.put("local_timings", new JSONArray(timings));
			report.put("diagnostics", new JSONArray(BleDiagnostics.inspect(context)));
			report.put("stages", new JSONObject(stages));
			report.put("radio", radio.counters());
			JSONArray records = new JSONArray();
			database.close();
			try (KeyringDatabase reopened = new KeyringDatabase(context, name)) {
				for (SavedEncounter saved : original) {
					SavedEncounter retained = reopened.find(saved.encounter.eventId);
					check(retained.phase == Phase.CONFIRMED && retained.encounter.sameAs(saved.encounter)
						&& retained.submissionId.equals(saved.submissionId)
						&& retained.evidence.payload.equals(saved.evidence.payload), "SQLite original survives reopen");
					check(!reopened.feedbackState(saved.encounter.eventId).isEmpty()
						&& !reopened.claimFeedback(saved.encounter.eventId, "CLAIMED"), "feedback cannot replay");
					check(retained.encounter.independent && !retained.encounter.isContract(), "local-only storage");
					records.put(new JSONObject().put("eventId", retained.encounter.eventId.toString())
						.put("deviceA", retained.encounter.deviceA.toString())
						.put("deviceB", retained.encounter.deviceB.toString())
						.put("peerStorageVerified", false));
				}
			}
			report.put("records", records);
			return report.toString();
		} finally {
			CountDownLatch stopped = new CountDownLatch(1);
			handler.post(() -> {
				handler.removeCallbacksAndMessages(null);
				if (radio != null) {
					radio.close();
				}
				stopped.countDown();
			});
			stopped.await(5, TimeUnit.SECONDS);
			thread.quitSafely();
			thread.join(5000);
			if (database != null) {
				database.close();
			}
			context.deleteDatabase(name);
		}
	}

	private void tick() {
		try {
			boolean complete = database.list().size() == peers && database.list().stream().allMatch(saved ->
				saved.phase == Phase.CONFIRMED && !database.feedbackState(saved.encounter.eventId).isEmpty());
			if (complete && completedAt == 0) {
				completedAt = SystemClock.elapsedRealtime();
			}
			// Measure completion separately from this duplicate-reception observation period.
			if (complete && SystemClock.elapsedRealtime() - completedAt >= 10000) {
				finished.countDown();
			} else {
				handler.postDelayed(this::tick, 1000);
			}
		} catch (Throwable exception) {
			failed(exception);
		}
	}

	@Override
	public void onStatus(String message) {
		statuses.add(message);
	}

	@Override
	public void onPacket(AdvertisementCodec.Envelope envelope) {
		try {
			check(envelope.packet.type == Packet.PROFILE_BROADCAST, "only profiles received");
			database.recordTraffic("RX", envelope.source, envelope.packet, envelope.messageId);
			matcher.receive(envelope.packet.profile);
			if (database.list().size() == peers && completedAt == 0) {
				completedAt = SystemClock.elapsedRealtime();
			}
		} catch (Throwable exception) {
			failed(exception);
		}
	}

	private void process(FlowEvent event) {
		stages.merge(event.code, 1, Integer::sum);
		if (event.code.equals("LOCAL_SAVED")) {
			timings.add(event.detail);
		}
		database.recordProcess(event);
	}

	@Override
	public void onTransmitted(AdvertisementOutbox.Message message) {
		try {
			check(message.packet.type == Packet.PROFILE_BROADCAST, "no handshake or responses transmitted");
			database.recordTraffic("TX", message.recipient, message.packet, message.id);
		} catch (Throwable exception) {
			failed(exception);
		}
	}

	@Override
	public void onClosed() {
		// Starting and stopping the radio do not delete test records.
	}

	private void failed(Throwable exception) {
		failure.compareAndSet(null, exception);
		finished.countDown();
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
