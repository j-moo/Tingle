package com.e106.keyring.lab;

import java.util.UUID;

import com.e106.keyring.core.DemoProfile;
import com.e106.keyring.core.Encounter;
import com.e106.keyring.core.IndependentMatcher;
import com.e106.keyring.core.MatchWork;
import com.e106.keyring.core.MatchingRule;
import com.e106.keyring.core.Packet;
import com.e106.keyring.core.Peer;
import com.e106.keyring.core.SavedEncounter;

import android.content.Context;

/** V5 originals and new failed jobs survive upgrade/reopen without changing identity or proof. */
final class LocalStorageVerification {
	private LocalStorageVerification() {
	}

	static void verify(Context context, String name) {
		long[] clock = {1000};
		UUID localId;
		UUID oldId;
		UUID failedEvent;
		String oldEvidence;
		DemoProfile local;
		DemoProfile remote = DemoProfile.fixture(new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true));
		try {
			try (KeyringDatabase database = new KeyringDatabase(context, name)) {
				local = database.fixture();
				localId = local.keyring.deviceId;
				DemoProfile other = DemoProfile.fixture(new Peer(UUID.randomUUID(), UUID.randomUUID(), 1, true));
				DemoProfile first = local.userId.toString().compareTo(other.userId.toString()) < 0 ? local : other;
				DemoProfile second = first == local ? other : local;
				Encounter old = new Encounter(UUID.randomUUID(), first, second, 1000, UUID.randomUUID());
				SavedEncounter original = database.save(old, SavedEncounter.Phase.CONFIRMED);
				oldId = old.eventId;
				oldEvidence = original.evidence.payload;
				database.recordTraffic("RX", other.keyring.deviceId, Packet.profile(other), "migration-original");
				// Exactly the prior v5 schema: v6 adds only this separate job table.
				database.getWritableDatabase().execSQL("DROP TABLE processing_jobs");
				database.getWritableDatabase().setVersion(5);
			}
			try (KeyringDatabase database = new KeyringDatabase(context, name)) {
				check(database.getReadableDatabase().getVersion() == 6, "v5 migration");
				check(database.identity(false).deviceId.equals(localId), "identity unchanged");
				check(database.find(oldId).evidence.payload.equals(oldEvidence), "old evidence unchanged");
				check(database.traffic(true, false).size() == 1, "old traffic retained");
				IndependentMatcher matcher = new IndependentMatcher(local, database, (first, second) -> {
					throw new IllegalStateException("INJECTED_RULE_FAILURE");
				}, () -> clock[0], database::recordProcess);
				matcher.receive(remote);
				MatchWork work = database.pendingWork().get(0);
				failedEvent = work.eventId;
				check(work.remote.sameAs(remote) && work.local.sameAs(local) && work.retryAt == 2000,
					"failed input and deadline persisted");
			}
			try (KeyringDatabase database = new KeyringDatabase(context, name)) {
				IndependentMatcher matcher = new IndependentMatcher(local, database, MatchingRule.alwaysMatch(),
					() -> clock[0], database::recordProcess);
				matcher.restore();
				check(database.find(failedEvent) == null, "no premature retry after restart");
				clock[0] = 2000;
				matcher.retryDue();
				check(database.find(failedEvent).encounter.independent && database.pendingWork().isEmpty(),
					"failed work resumed to local upload pending");
				check(database.find(oldId).evidence.payload.equals(oldEvidence), "new flow never rewrites old record");
				check(database.feedbackState(failedEvent).equals("CLAIMED"), "local alert claimed once");
				for (TrafficEntry entry : database.traffic(false, false)) {
					try {
						entry.toJson();
					} catch (org.json.JSONException exception) {
						throw new AssertionError("JSON export", exception);
					}
				}
			}
		} finally {
			context.deleteDatabase(name);
		}
	}

	private static void check(boolean valid, String message) {
		if (!valid) {
			throw new AssertionError(message);
		}
	}
}
