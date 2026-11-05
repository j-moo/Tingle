package com.e106.keyring.lab;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONObject;

import com.e106.keyring.core.AdvertisementCodec;
import com.e106.keyring.core.AdvertisementOutbox;
import com.e106.keyring.core.DemoProfile;
import com.e106.keyring.core.Encounter;
import com.e106.keyring.core.FlowEvent;
import com.e106.keyring.core.IndependentMatcher;
import com.e106.keyring.core.LocalEvidence;
import com.e106.keyring.core.MatchWork;
import com.e106.keyring.core.MatchingRule;
import com.e106.keyring.core.Packet;
import com.e106.keyring.core.Peer;
import com.e106.keyring.core.SavedEncounter;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

public final class LabController implements BleTransport.Listener {
	private final Context context;
	private final HandlerThread thread = new HandlerThread("keyring-lab");
	private final Handler main = new Handler(Looper.getMainLooper());
	private final Handler worker;
	private final Consumer<Snapshot> observer;
	private final KeyringDatabase database;
	private final SharedPreferences preferences;
	private final BleTransport transport;
	private final Map<UUID, PeerState> peers = new LinkedHashMap<>();
	private final List<String> logs = new ArrayList<>();
	private IndependentMatcher matcher;
	private final Runnable retryFailed = this::retryDue;
	private final List<UUID> feedbackRequests = new ArrayList<>();
	private DemoProfile profile;
	private Peer local;
	private UUID selected;
	private String groupCode;
	private String status = "시험 준비 · 양쪽에 같은 그룹 코드를 설정해주세요";
	private String error = "";
	private volatile boolean disposed;
	private long successSerial;
	private int historyLimit = 80;

	public static final class Snapshot {
		public String status;
		public String error;
		public String groupCode;
		public String counters;
		public String receptionStatus;
		public String automaticStatus;
		public Peer local;
		public Peer remote;
		public boolean connected;
		public boolean running;
		public boolean active;
		public List<String> diagnostics;
		public List<SavedEncounter> records;
		public List<String> logs;
		public List<TrafficEntry> traffic;
		public boolean hasOlderTraffic;
		public long successSerial;
		public long feedbackStartedAt;
		public List<UUID> feedbackRequests;
		public Map<String, String> candidates;
	}

	public LabController(Context context, Consumer<Snapshot> observer) {
		this.context = context.getApplicationContext();
		this.observer = observer;
		thread.start();
		worker = new Handler(thread.getLooper());
		database = new KeyringDatabase(this.context, "keyring-lab.db");
		preferences = this.context.getSharedPreferences("lab-membership", Context.MODE_PRIVATE);
		transport = new BleTransport(this.context, worker, this);
		run(() -> {
			local = database.identity(false);
			groupCode = preferences.getString("group", "");
			onFlow(new FlowEvent("BOOT", null, null,
				"registration=SKIPPED; synchronization=SKIPPED; legacy_records=preserved"));
		});
		worker.postDelayed(this::tick, 2000);
	}

	public void setGroup(String code) {
		run(() -> {
			String normalized = AdvertisementCodec.normalize(code);
			transport.close();
			if (!preferences.edit().putString("group", normalized).commit()) {
				throw new IllegalStateException("그룹 코드 저장에 실패했습니다.");
			}
			groupCode = normalized;
			status = "그룹 코드 저장됨 · 양쪽에서 광고·스캔을 시작하세요";
			log("시험 그룹 변경 · 기존 만남 기록 보존");
		});
	}

	public void prepare() {
		run(() -> {
			if (transport.isRunning()) {
				throw new IllegalStateException("설정을 바꾸려면 먼저 OFF 해주세요.");
			}
			local = database.prepare();
			log("시험 설정 저장 완료 · 기기 시계 사용 / 서버 동기화 아님");
		});
	}

	public void start(Consumer<Boolean> completion) {
		run(() -> {
			try {
				if (local == null) {
					throw new IllegalStateException("저장소를 확인한 뒤 다시 시작해주세요.");
				}
				if (!local.ready) {
					// ON explicitly applies the unchanged lab fixture to durable storage before starting BLE.
					local = database.prepare();
					log("ON · 시험 설정 저장 완료 / 기기 시계 사용");
				}
				profile = database.fixture();
				onFlow(new FlowEvent("FIXTURE_READY", null, null, fixtureJson()));
				transport.start(profile, groupCode);
				matcher = new IndependentMatcher(profile, database, MatchingRule.alwaysMatch(),
					System::currentTimeMillis, this::onFlow);
				matcher.restore();
				scheduleRetry();
			} finally {
				boolean active = transport.isRunning();
				main.post(() -> completion.accept(active));
			}
		});
	}

	public void reportFailure(String message) {
		run(() -> {
			throw new IllegalStateException(message);
		});
	}

	public void select(String token) {
		run(() -> {
			UUID id = UUID.fromString(token);
			if (!peers.containsKey(id)) {
				throw new IllegalStateException("상대가 탐색 목록에서 사라졌습니다.");
			}
			selected = id;
			log("시험 상대 선택 · " + token.substring(0, 8));
		});
	}

	public void stop() {
		run(() -> {
			transport.close();
			status = "광고·스캔 중지 · 기록은 유지됩니다";
		});
	}

	public void ping() {
		run(() -> log("자기 데이터 반복 광고 중 · 응답 요청 없이 수신 후 독립 판정합니다."));
	}

	public void retry() {
		run(() -> {
			if (matcher != null && transport.isRunning()) {
				matcher.retryFailures();
				scheduleRetry();
			}
		});
	}

	public void loadOlderTraffic() {
		run(() -> historyLimit += 80);
	}

	public void refresh() {
		run(() -> {
			if (!BleDiagnostics.hasPermissions(context) || !BleDiagnostics.locationEnabled(context)) {
				transport.close();
				status = "광고·스캔 중지 · BLE 권한과 위치 서비스를 확인하세요";
			}
		});
	}

	public void export(Uri uri) {
		run(() -> {
			try {
				JSONObject root = new JSONObject();
				root.put("format", "virtual-keyring-lab-4");
				root.put("productionUploadAllowed", false);
				root.put("exportedAt", Instant.now().toString());
				root.put("deviceId", local.deviceId.toString());
				JSONArray records = new JSONArray();
				for (SavedEncounter record : database.list()) {
					Encounter event = record.encounter;
					JSONObject value = new JSONObject();
					value.put("eventId", event.eventId.toString());
					value.put("submissionId", record.submissionId.toString());
					value.put("deviceA", event.deviceA.toString());
					value.put("deviceB", event.deviceB.toString());
					value.put("occurredAt", Instant.ofEpochMilli(event.occurredAt).toString());
					value.put("snapshotA", event.snapshotA.toString());
					value.put("snapshotB", event.snapshotB.toString());
					value.put("settingsVersionA", event.versionA);
					value.put("settingsVersionB", event.versionB);
					value.put("phase", record.phase.name());
					value.put("source", "BLE_LAB");
					value.put("contractEvidenceAvailable", event.isContract() && record.evidence != null);
					value.put("independent", event.independent);
					value.put("peerStorageVerified",
						event.isContract() && record.phase == SavedEncounter.Phase.CONFIRMED);
					value.put("uploadState", event.independent ? "LOCAL_PENDING"
						: event.isContract() ? "PENDING_SERVICE_APP" : "LEGACY_NOT_VERIFIABLE");
					if (record.evidence != null) {
						value.put("user_a_id", event.profileA.userId.toString());
						value.put("user_b_id", event.profileB.userId.toString());
						value.put("keyring_a_id", event.deviceA.toString());
						value.put("keyring_b_id", event.deviceB.toString());
						value.put("matched_at", Instant.ofEpochMilli(event.occurredAt).toString());
						value.put("expires_at", Instant.ofEpochMilli(event.expiresAt).toString());
						value.put("policyVersion", event.profileA.policyVersion);
						value.put("matchingRuleVersion", event.profileA.ruleVersion);
						value.put("settingsSnapshotId", record.evidence.snapshotId.toString());
						value.put("peerCredential", record.evidence.peerCredential);
						value.put("evidence", new JSONObject().put("protocolVersion",
							event.independent ? LocalEvidence.INDEPENDENT_PROTOCOL : LocalEvidence.PROTOCOL)
							.put("payload", record.evidence.payload));
						value.put("feedbackState", database.feedbackState(event.eventId));
					}
					records.put(value);
				}
				root.put("encounters", records);
				root.put("serverSubmissionPreview", submissionPreview());
				root.put("localPending", localPendingJson());
				root.put("processingQueue", processingJson());
				root.put("diagnostics", new JSONArray(BleDiagnostics.inspect(context)));
				try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
					if (output == null) {
						throw new IllegalStateException("파일을 열 수 없습니다.");
					}
					output.write((root.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
				}
				log("JSON 내보내기 완료 · 그룹 코드는 내보내지 않습니다 / 원본 기록 유지");
			} catch (Exception exception) {
				throw new IllegalStateException("내보내기 실패: " + exception.getClass().getSimpleName(), exception);
			}
		});
	}

	public void exportReceived(Uri uri, Consumer<String> completion) {
		run(() -> {
			String message;
			try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
				if (output == null) {
					throw new IllegalStateException("파일을 열 수 없습니다.");
				}
				output.write(receivedJson().getBytes(StandardCharsets.UTF_8));
				message = "받은 데이터 JSON을 저장했어요.";
			} catch (Exception exception) {
				message = "JSON 저장에 실패했어요. 저장 위치와 여유 공간을 확인해주세요.";
				error = message;
			}
			log(message);
			String result = message;
			main.post(() -> completion.accept(result));
		});
	}

	public void shareReceived(Consumer<Uri> callback) {
		run(() -> {
			try {
				Uri uri = JsonExportProvider.write(context, receivedJson());
				main.post(() -> callback.accept(uri));
			} catch (Exception exception) {
				throw new IllegalStateException("JSON 공유 파일을 만들지 못했습니다.", exception);
			}
		});
	}

	private String receivedJson() throws org.json.JSONException {
		JSONObject root = new JSONObject();
		root.put("format", "virtual-keyring-independent-timeline-1");
		root.put("productionUploadAllowed", false);
		root.put("exportedAt", Instant.now().toString());
		root.put("deviceId", local.deviceId.toString());
		JSONArray messages = new JSONArray();
		List<TrafficEntry> entries = database.traffic(true, false);
		for (int index = entries.size() - 1; index >= 0; index--) {
			messages.put(entries.get(index).toJson());
		}
		root.put("receivedMessages", messages);
		JSONArray timeline = new JSONArray();
		List<TrafficEntry> all = database.traffic(false, false);
		for (int index = all.size() - 1; index >= 0; index--) {
			timeline.put(all.get(index).toJson());
		}
		root.put("timeline", timeline);
		root.put("registration", "SKIPPED");
		root.put("synchronization", "SKIPPED");
		root.put("matchingRuleVersion", DemoProfile.RULE);
		root.put("serverSubmissionPreview", submissionPreview());
		root.put("localPending", localPendingJson());
		root.put("processingQueue", processingJson());
		return root.toString(2) + "\n";
	}

	private JSONArray localPendingJson() throws org.json.JSONException {
		JSONArray result = new JSONArray();
		for (SavedEncounter record : database.list()) {
			if (record.encounter.independent) {
				result.put(new JSONObject().put("eventId", record.encounter.eventId.toString())
					.put("submissionId", record.submissionId.toString()).put("uploadState", "LOCAL_PENDING")
					.put("peerStorageVerified", false)
					.put("matchedAt", Instant.ofEpochMilli(record.encounter.occurredAt).toString())
					.put("profileA", TrafficEntry.profileJson(record.encounter.profileA))
					.put("profileB", TrafficEntry.profileJson(record.encounter.profileB))
					.put("feedbackState", database.feedbackState(record.encounter.eventId)));
			}
		}
		return result;
	}

	private JSONArray processingJson() throws org.json.JSONException {
		JSONArray result = new JSONArray();
		List<MatchWork> jobs = matcher == null ? database.pendingWork() : matcher.failures();
		for (MatchWork work : jobs) {
			result.put(new JSONObject().put("jobId", work.id.toString()).put("eventId", work.eventId.toString())
				.put("stage", work.stage.name()).put("attempts", work.attempts).put("retryAt", work.retryAt)
				.put("error", work.error).put("receivedAt", work.receivedAt)
				.put("local", TrafficEntry.profileJson(work.local))
				.put("remote", TrafficEntry.profileJson(work.remote)));
		}
		return result;
	}

	private JSONObject submissionPreview() throws org.json.JSONException {
		JSONArray submissions = new JSONArray();
		for (SavedEncounter record : database.list()) {
			if (record.encounter.isContract() && record.phase == SavedEncounter.Phase.CONFIRMED) {
				submissions.put(ContractJson.submission(record));
			}
		}
		return new JSONObject().put("deviceId", local.deviceId.toString()).put("submissions", submissions);
	}

	private void run(Runnable action) {
		worker.post(() -> {
			if (disposed) {
				return;
			}
			error = "";
			try {
				action.run();
			} catch (RuntimeException exception) {
				error = exception instanceof SQLiteException ? "저장소 오류 · 성공으로 처리하지 않았습니다."
					: String.valueOf(exception.getMessage());
				log(error);
			}
			publish();
		});
	}

	private String fixtureJson() {
		try {
			return TrafficEntry.profileJson(profile).toString();
		} catch (org.json.JSONException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private void onFlow(FlowEvent event) {
		try {
			database.recordProcess(event);
		} catch (RuntimeException exception) {
			error = "과정 로그 저장 실패 · 오류 작업 입력은 별도로 보존합니다.";
		}
		appendLog(FlowText.describe(event.code));
		if ("FEEDBACK_READY".equals(event.code)) {
			feedbackRequests.add(event.eventId);
			successSerial++;
		}
	}

	public void beginFeedback(UUID eventId, Consumer<Boolean> completion) {
		run(() -> {
			SavedEncounter record = database.find(eventId);
			boolean valid = transport.isRunning() && record != null
				&& record.encounter.expiresAt > System.currentTimeMillis()
				&& "CLAIMED".equals(database.feedbackState(eventId));
			database.finishFeedback(eventId, valid ? "STARTED" : "SKIPPED_OFF_OR_EXPIRED");
			onFlow(new FlowEvent(valid ? "FEEDBACK_STARTED" : "FEEDBACK_SKIPPED", null, eventId,
				"local_only=true; proximity_recheck=false; pattern_ms=500,500,500"));
			main.post(() -> completion.accept(valid));
		});
	}

	public void finishFeedback(UUID eventId, String result) {
		run(() -> {
			database.finishFeedback(eventId, result);
			onFlow(new FlowEvent("FINISHED".equals(result) ? "FEEDBACK_FINISHED" : "FEEDBACK_STOPPED",
				null, eventId, "state=" + result));
		});
	}

	public void noVibrator(UUID eventId) {
		run(() -> onFlow(new FlowEvent("NO_VIBRATOR", null, eventId, "hardware_motor=false; visual_feedback=true")));
	}

	private void log(String message) {
		try {
			database.recordProcess(new FlowEvent("STATUS", null, null, message));
		} catch (RuntimeException ignored) {
			// Storage failure remains visible in the in-memory diagnostics.
		}
		appendLog(message);
	}

	private void appendLog(String message) {
		String time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now());
		logs.add(0, time + "  " + message);
		if (logs.size() > 50) {
			logs.remove(logs.size() - 1);
		}
	}

	@Override
	public void onStatus(String message) {
		status = message;
		log(message);
		publish();
	}

	@Override
	public void onPacket(AdvertisementCodec.Envelope envelope) {
		if (!transport.isRunning() || matcher == null || envelope.packet.type != Packet.PROFILE_BROADCAST) {
			return;
		}
		PeerState state = peers.get(envelope.source);
		if (state != null && envelope.sentAt < state.sentAt) {
			return;
		}
		if (state == null) {
			if (peers.size() >= 128) {
				peers.remove(peers.keySet().iterator().next());
			}
			state = new PeerState();
			peers.put(envelope.source, state);
			recordTraffic("DISCOVERY", envelope.source, envelope.packet, envelope.messageId);
			if (selected == null) {
				selected = envelope.source;
			}
		}
		state.profile = envelope.packet.profile;
		state.sentAt = envelope.sentAt;
		state.lastSeen = SystemClock.elapsedRealtime();
		recordTraffic("RX", envelope.source, envelope.packet, envelope.messageId);
		matcher.receive(envelope.packet.profile);
		scheduleRetry();
		status = "광고·스캔 병행 · " + peers.size() + "대 수신 · 내 기기에서 독립 판정";
		publish();
	}

	@Override
	public void onTransmitted(AdvertisementOutbox.Message message) {
		recordTraffic("TX", message.recipient, message.packet, message.id);
		publish();
	}

	@Override
	public void onAssemblyWaiting(int pending) {
		onFlow(new FlowEvent("RECEIVE_ASSEMBLY", null, null,
			"pending_messages=" + pending + "; matching_started=false; encrypted_buffer_ttl_ms=150000"));
	}

	private void recordTraffic(String direction, UUID peer, Packet packet, String messageKey) {
		try {
			database.recordTraffic(direction, peer, packet, messageKey);
		} catch (RuntimeException exception) {
			log("통신 로그 저장 실패 · 만남 기록과 통신 처리는 별도로 계속합니다.");
		}
	}

	@Override
	public void onClosed() {
		worker.removeCallbacks(retryFailed);
		matcher = null;
		peers.clear();
		selected = null;
	}

	private void scheduleRetry() {
		worker.removeCallbacks(retryFailed);
		if (matcher != null && transport.isRunning() && matcher.nextRetryAt() != 0) {
			worker.postDelayed(retryFailed, Math.max(1, matcher.nextRetryAt() - System.currentTimeMillis()));
		}
	}

	private void retryDue() {
		if (matcher != null && transport.isRunning()) {
			matcher.retryDue();
			scheduleRetry();
			publish();
		}
	}

	private void tick() {
		if (!disposed) {
			// UI diagnostics only. Incoming jobs run synchronously; only failed jobs get a retry timer.
			publish();
			worker.postDelayed(this::tick, 2000);
		}
	}

	private void publish() {
		if (disposed) {
			return;
		}
		Snapshot snapshot = new Snapshot();
		snapshot.status = status;
		snapshot.local = local;
		snapshot.groupCode = groupCode == null ? "" : groupCode;
		snapshot.running = transport.isReady();
		snapshot.active = transport.isRunning();
		snapshot.counters = transport.counters();
		snapshot.receptionStatus = transport.receptionStatus();
		snapshot.successSerial = successSerial;
		snapshot.feedbackRequests = new ArrayList<>(feedbackRequests);
		PeerState selectedState = peers.get(selected);
		snapshot.remote = selectedState == null || selectedState.profile == null ? null
			: selectedState.profile.keyring;
		snapshot.connected = transport.isReady() && snapshot.remote != null
			&& SystemClock.elapsedRealtime() - selectedState.lastSeen <= 150000;
		snapshot.candidates = new LinkedHashMap<>();
		for (UUID id : peers.keySet()) {
			if (SystemClock.elapsedRealtime() - peers.get(id).lastSeen <= 150000) {
				snapshot.candidates.put(id.toString(), (id.equals(selected) ? "● " : "○ ")
					+ "가상 키링 " + id.toString().substring(0, 8));
			}
		}
		snapshot.diagnostics = BleDiagnostics.inspect(context);
		try {
			snapshot.records = database.list();
			snapshot.traffic = database.traffic(false, historyLimit);
			snapshot.hasOlderTraffic = database.trafficCount() > snapshot.traffic.size();
		} catch (RuntimeException exception) {
			snapshot.records = new ArrayList<>();
			snapshot.traffic = new ArrayList<>();
			error = "기존 기록을 읽을 수 없습니다. 데이터는 삭제하지 않았습니다.";
		}
		snapshot.error = error;
		snapshot.automaticStatus = automaticStatus(snapshot);
		snapshot.logs = new ArrayList<>(logs);
		main.post(() -> {
			if (!disposed) {
				observer.accept(snapshot);
			}
		});
	}

	private String automaticStatus(Snapshot snapshot) {
		if (!snapshot.running) {
			return "광고·스캔 준비 중";
		}
		PeerState state = peers.get(selected);
		return (state == null ? "주변 키링 데이터를 기다려요" : "수신 즉시 독립 판정 · 내 기기에 저장")
			+ (matcher == null || matcher.errorCount() == 0 ? "" : " · 오류 재처리 " + matcher.errorCount() + "건");
	}

	public void dispose() {
		worker.post(() -> {
			disposed = true;
			transport.close();
			database.close();
			worker.removeCallbacksAndMessages(null);
			thread.quitSafely();
		});
	}

	private static final class PeerState {
		private long lastSeen;
		private long sentAt;
		private DemoProfile profile;
	}
}
