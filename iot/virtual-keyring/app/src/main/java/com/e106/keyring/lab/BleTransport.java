package com.e106.keyring.lab;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.e106.keyring.core.AdvertisementCodec;
import com.e106.keyring.core.AdvertisementFrames;
import com.e106.keyring.core.AdvertisementOutbox;
import com.e106.keyring.core.DemoProfile;
import com.e106.keyring.core.ExtendedAdvertisement;
import com.e106.keyring.core.Packet;
import com.e106.keyring.core.Peer;
import com.e106.keyring.core.ProfileBroadcaster;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.SystemClock;

/** Filtered scanning plus non-connectable extended data and a legacy discovery/compatibility lane. */
public final class BleTransport {
	private static final ParcelUuid SERVICE = new ParcelUuid(AdvertisementCodec.SERVICE);
	private final Context context;
	private final Handler worker;
	private final Listener listener;
	private final Map<String, Long> seen = new LinkedHashMap<>();
	private final Set<String> transmittedMessages = new LinkedHashSet<>();
	private BluetoothLeScanner scanner;
	private BluetoothLeAdvertiser advertiser;
	private ScanCallback scanCallback;
	private RadioLane legacyLane;
	private RadioLane extendedLane;
	private AdvertisementCodec codec;
	private AdvertisementFrames incoming;
	private ProfileBroadcaster outbox;
	private int maximumLength;
	private boolean extendedScan;
	private String radioMode = "통신 꺼짐";
	private long fullReceived;
	private long fullTransmitted;
	private long truncated;
	private Peer local;
	private long radioSession;
	private int generation;
	private boolean running;
	private long received;
	private long transmitted;
	private long rejected;
	private long scanResults;
	private long assembled;
	private long accepted;
	private long future;
	private long expired;
	private long otherSession;
	private long startedAt;
	private long lastAcceptedAt = -1;
	private long lastClockErrorAt = -1;
	private long lastAssemblyLog = -10000;

	public interface Listener {
		void onStatus(String message);

		void onPacket(AdvertisementCodec.Envelope envelope);

		void onTransmitted(AdvertisementOutbox.Message message);

		void onClosed();

		default void onAssemblyWaiting(int pending) {
			// Partial ciphertext has no authenticated identity yet and never enters matching.
		}
	}

	public BleTransport(Context context, Handler worker, Listener listener) {
		this.context = context;
		this.worker = worker;
		this.listener = listener;
	}

	public void start(DemoProfile profile, String groupCode) {
		Peer peer = profile.keyring;
		close();
		if (!BleDiagnostics.hasPermissions(context)) {
			throw new IllegalStateException("BLE 권한을 먼저 허용해주세요.");
		}
		BluetoothAdapter adapter = BleDiagnostics.adapter(context);
		if (adapter == null || !adapter.isEnabled() || !BleDiagnostics.locationEnabled(context)) {
			throw new IllegalStateException("Bluetooth와 필요한 위치 서비스를 켜주세요.");
		}
		scanner = adapter.getBluetoothLeScanner();
		advertiser = adapter.getBluetoothLeAdvertiser();
		if (scanner == null || advertiser == null) {
			throw new IllegalStateException("이 기기는 광고와 스캔을 모두 사용할 수 없습니다.");
		}
		codec = new AdvertisementCodec(groupCode);
		incoming = new AdvertisementFrames();
		local = peer;
		radioSession = AdvertisementCodec.newSession();
		outbox = new ProfileBroadcaster(codec, profile, radioSession);
		boolean supportsExtended = adapter.isLeExtendedAdvertisingSupported();
		maximumLength = adapter.getLeMaximumAdvertisingDataLength();
		extendedScan = supportsExtended;
		radioMode = "일반 광고 · 확장 광고 진단 중";
		running = true;
		received = 0;
		transmitted = 0;
		rejected = 0;
		scanResults = 0;
		assembled = 0;
		accepted = 0;
		future = 0;
		expired = 0;
		otherSession = 0;
		fullReceived = 0;
		fullTransmitted = 0;
		truncated = 0;
		startedAt = SystemClock.elapsedRealtime();
		lastAcceptedAt = -1;
		lastAssemblyLog = -10000;
		lastClockErrorAt = -1;
		int active = generation;
		scanCallback = new ScanCallback() {
			@Override
			public void onScanResult(int type, ScanResult result) {
				deliver(Arrays.asList(result), active);
			}

			@Override
			public void onBatchScanResults(List<ScanResult> results) {
				deliver(results, active);
			}

			@Override
			public void onScanFailed(int code) {
				worker.post(() -> {
					if (active == generation) {
						if (extendedScan && code == SCAN_FAILED_FEATURE_UNSUPPORTED) {
							extendedScan = false;
							disableExtended("확장 스캔 미지원 보고 · 일반 광고로 전환");
							try {
								scanner.stopScan(this);
								scanner.startScan(scanFilters(), scanSettings(false), this);
							} catch (SecurityException exception) {
								fail("BLE 권한이 변경되었습니다. 다시 허용해주세요.");
							} catch (RuntimeException exception) {
								fail("일반 스캔 재시작 실패 · " + exception.getClass().getSimpleName());
							}
						} else {
							fail("스캔 시작 실패 " + code + " · 잠시 후 다시 시작해주세요.");
						}
					}
				});
			}
		};
		try {
			scanner.startScan(scanFilters(), scanSettings(extendedScan), scanCallback);
			legacyLane = new RadioLane(false, active);
			legacyLane.start();
			if (!running) {
				return;
			}
			if (ExtendedAdvertisement.supported(supportsExtended, maximumLength)) {
				extendedLane = new RadioLane(true, active);
				extendedLane.start();
			} else {
				radioMode = "일반 광고 조각 전송 · 확장 광고 미지원/크기 부족";
			}
			listener.onStatus("광고·스캔 시작 요청 · 지원 보고=" + supportsExtended
				+ "; 최대=" + maximumLength + "B; 실제 콜백 대기");
		} catch (SecurityException exception) {
			close();
			throw new IllegalStateException("BLE 권한을 다시 허용해주세요.", exception);
		} catch (RuntimeException exception) {
			close();
			throw exception;
		}
	}

	static ScanSettings scanSettings(boolean extended) {
		return new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
			.setLegacy(!extended).setPhy(BluetoothDevice.PHY_LE_1M)
			.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setReportDelay(0).build();
	}

	static List<ScanFilter> scanFilters() {
		// OR filters: a standard service UUID path as well as the existing service-data path.
		// Keep filtering enabled for screen-off scans. A UUID alone never authenticates a peer.
		return Arrays.asList(new ScanFilter.Builder().setServiceUuid(SERVICE).build(),
			new ScanFilter.Builder().setServiceData(SERVICE, new byte[] {AdvertisementFrames.MARKER}).build(),
			new ScanFilter.Builder().setServiceData(SERVICE, new byte[] {ExtendedAdvertisement.MARKER}).build(),
			new ScanFilter.Builder().setServiceData(SERVICE, new byte[] {0x30}, new byte[] {(byte)0xf8}).build());
	}

	static AdvertisingSetParameters advertisingParameters() {
		return advertisingParameters(false);
	}

	static AdvertisingSetParameters advertisingParameters(boolean extended) {
		return new AdvertisingSetParameters.Builder().setLegacyMode(!extended).setConnectable(false)
			.setScannable(!extended).setInterval(160)
			.setPrimaryPhy(BluetoothDevice.PHY_LE_1M).setSecondaryPhy(BluetoothDevice.PHY_LE_1M)
			.setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM).build();
	}

	static AdvertiseData scanResponse() {
		// Only the public app UUID is in SCAN_RSP; encrypted fragments remain in the legacy advertisement.
		return new AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
			.addServiceUuid(SERVICE).build();
	}

	private void deliver(List<ScanResult> results, int active) {
		worker.post(() -> {
			for (ScanResult result : results) {
				if (active != generation || !running) {
					return;
				}
				if (result != null) {
					receive(result);
				}
			}
		});
	}

	private void disableExtended(String reason) {
		if (extendedLane != null) {
			extendedLane.stop();
		}
		if (outbox != null) {
			outbox.configureExtended(false, maximumLength);
		}
		radioMode = "일반 광고 조각 전송 · " + reason;
		listener.onStatus("EXTENDED_FALLBACK · " + reason + " · 기록 보존");
	}

	private final class RadioLane {
		private final boolean full;
		private final int active;
		private AdvertisingSet set;
		private AdvertisingSetCallback callback;
		private AdvertisementOutbox.Message pending;
		private boolean stopped;
		private long operation;
		private int frameBytes;

		private RadioLane(boolean full, int active) {
			this.full = full;
			this.active = active;
		}

		private boolean valid() {
			return !stopped && active == generation && running;
		}

		private void start() {
			if (full) {
				// Capability is advertised only after the controller reports a successful start.
				outbox.configureExtended(true, maximumLength);
			}
			callback = new AdvertisingSetCallback() {
				@Override
				public void onAdvertisingSetStarted(AdvertisingSet value, int power, int status) {
					if (!valid()) {
						return;
					}
					if (status != ADVERTISE_SUCCESS || value == null) {
						failed("광고 시작 실패 code=" + status);
						return;
					}
					set = value;
					if (full) {
						radioMode = "확장 광고 한 묶음 + 일반 광고 호환 경로";
					}
					listener.onStatus((full ? "EXTENDED_STARTED" : "LEGACY_STARTED")
						+ " · " + (full ? "한 묶음 전송 시작 · 상한 251B (255B 이내)" : "발견·호환 광고 시작"));
					accepted();
				}

				@Override
				public void onAdvertisingDataSet(AdvertisingSet value, int status) {
					if (valid()) {
						if (status == ADVERTISE_SUCCESS) {
							accepted();
						} else {
							failed("광고 갱신 실패 code=" + status);
						}
					}
				}
			};
			try {
				advertiser.startAdvertisingSet(advertisingParameters(full), nextData(), full ? null : scanResponse(),
					null, null, callback, worker);
				watchdog();
			} catch (SecurityException exception) {
				throw exception;
			} catch (RuntimeException exception) {
				failed("광고 시작 예외 " + exception.getClass().getSimpleName());
			}
		}

		private AdvertiseData nextData() {
			byte[] frame = outbox.nextFrame(full, SystemClock.elapsedRealtime(), System.currentTimeMillis());
			pending = outbox.currentMessage(full);
			frameBytes = frame.length + ExtendedAdvertisement.SERVICE_OVERHEAD;
			return data(frame);
		}

		private void accepted() {
			operation++;
			transmitted++;
			if (full) {
				fullTransmitted++;
			}
			if (pending != null && transmittedMessages.add(pending.id)) {
				if (transmittedMessages.size() > 512) {
					transmittedMessages.remove(transmittedMessages.iterator().next());
				}
				listener.onTransmitted(pending);
				listener.onStatus("RADIO_TX · " + (full ? "EXTENDED_SINGLE" : "LEGACY_FRAGMENT")
					+ "; type=" + pending.packet.type + "; ad_bytes=" + frameBytes
					+ "; peer_received=false; message_id=" + pending.id);
			}
			worker.postDelayed(() -> {
				if (valid() && set != null) {
					try {
						set.setAdvertisingData(nextData());
						watchdog();
					} catch (SecurityException exception) {
						fail("BLE 권한이 변경되었습니다. 다시 허용해주세요.");
					} catch (RuntimeException exception) {
						failed("광고 갱신 예외 " + exception.getClass().getSimpleName());
					}
				}
			}, 300);
		}

		private void watchdog() {
			long request = ++operation;
			worker.postDelayed(() -> {
				if (valid() && request == operation) {
					failed("광고 응답 시간 초과");
				}
			}, 10000);
		}

		private void failed(String reason) {
			if (full) {
				disableExtended(reason);
			} else {
				fail("일반 광고 실패 · " + reason);
			}
		}

		private void stop() {
			stopped = true;
			set = null;
			if (advertiser != null && callback != null) {
				try {
					advertiser.stopAdvertisingSet(callback);
				} catch (SecurityException ignored) {
					// Revoked permission also invalidates every outstanding callback.
				} catch (RuntimeException ignored) {
					// Bluetooth/permission may already be gone; callbacks are invalidated independently.
				}
			}
		}
	}

	private AdvertiseData data(byte[] frame) {
		return new AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
			.addServiceData(SERVICE, frame).build();
	}


	public long session() {
		return radioSession;
	}

	private void receive(ScanResult result) {
		try {
			scanResults++;
			if (result.getScanRecord() == null) {
				return;
			}
			byte[] frame = result.getScanRecord().getServiceData(SERVICE);
			if (frame == null) {
				return;
			}
			received++;
			if (result.getDataStatus() != ScanResult.DATA_COMPLETE) {
				truncated++;
				return;
			}
			boolean full = frame.length > 0 && frame[0] == ExtendedAdvertisement.MARKER;
			byte[] encrypted = full ? ExtendedAdvertisement.unwrap(frame)
				: incoming.accept(result.getDevice().getAddress(), frame, SystemClock.elapsedRealtime());
			if (encrypted == null) {
				long now = SystemClock.elapsedRealtime();
				if (now - lastAssemblyLog >= 10000) {
					lastAssemblyLog = now;
					listener.onAssemblyWaiting(incoming.progress(now).pending);
				}
				return;
			}
			assembled++;
			AdvertisementCodec.Envelope envelope = codec.open(encrypted, System.currentTimeMillis());
			if (!envelope.forSession(local.deviceId, radioSession)) {
				otherSession++;
				return;
			}
			if (envelope.packet.type != Packet.PROFILE_BROADCAST) {
				rejected++;
				return;
			}
			String nonce = envelope.messageId;
			long now = SystemClock.elapsedRealtime();
			seen.entrySet().removeIf(entry -> now - entry.getValue() > AdvertisementCodec.MAX_AGE_MS);
			if (seen.containsKey(nonce)) {
				return;
			}
			if (seen.size() >= 512) {
				seen.remove(seen.keySet().iterator().next());
			}
			seen.put(nonce, now);
			accepted++;
			if (full) {
				fullReceived++;
			}
			lastAcceptedAt = now;
			listener.onPacket(envelope);
		} catch (AdvertisementCodec.ClockException exception) {
			if (exception.future) {
				future++;
			} else {
				expired++;
			}
			lastClockErrorAt = SystemClock.elapsedRealtime();
		} catch (IllegalArgumentException exception) {
			// Foreign groups, corrupt fragments and invalid packets are silent: never reply to them.
			rejected++;
		} catch (SecurityException exception) {
			fail("BLE 권한이 변경되었습니다. 다시 허용해주세요.");
		} catch (RuntimeException exception) {
			listener.onStatus("수신 처리 오류 · 진단 기록 확인");
		}
	}

	public boolean isReady() {
		return running && legacyLane != null && legacyLane.set != null;
	}

	public boolean isRunning() {
		return running;
	}

	public String counters() {
		AdvertisementFrames.Progress progress = incoming == null ? null
			: incoming.progress(SystemClock.elapsedRealtime());
		String partial = progress == null || progress.pending == 0 ? "없음"
			: progress.received + "/" + progress.total + "조각 · 미완성 묶음 " + progress.pending;
		return radioMode + "\n확장 한 묶음 TX " + fullTransmitted + " / 인증 RX " + fullReceived
			+ " · 불완전 광고 제외 " + truncated + " · 내 데이터 반복 광고 " + (outbox == null ? 0 : 1)
			+ "\n전체 광고 갱신 " + transmitted + " · 필터 응답 " + scanResults + " · 수신 데이터 " + received
			+ "\n조립 완료 " + assembled + " · 인증 수신 " + accepted + " · 인증/형식 제외 " + rejected
			+ "\n시각 제외: 미래 " + future + " / 만료 " + expired + " · 다른 대상/세션 " + otherSession
			+ "\n가장 많이 모은 데이터: " + partial;
	}

	public String receptionStatus() {
		if (!running) {
			return "통신 꺼짐 · 진단 숫자는 마지막 실행 기준입니다.";
		}
		if (lastClockErrorAt >= 0 && lastClockErrorAt > lastAcceptedAt) {
			return "수신 시각 확인 필요 · 양쪽 날짜 및 시간을 자동으로 맞춘 뒤 OFF → ON해주세요.";
		}
		if (lastAcceptedAt >= 0) {
			long seconds = (SystemClock.elapsedRealtime() - lastAcceptedAt) / 1000;
			return "같은 그룹 데이터 수신 확인 · 마지막 수신 " + seconds + "초 전";
		}
		if (rejected > 0) {
			return "인증/형식 제외 발생 · 양쪽의 전체 그룹 코드와 앱 버전을 확인해주세요.";
		}
		if (received > 0) {
			return "광고 조각 도착 · 완전한 데이터 조립 대기. 가까이 두고 수신 증가를 확인해주세요.";
		}
		if (SystemClock.elapsedRealtime() - startedAt < 20000) {
			return "상대 광고 수신 대기 · 양쪽 ON 상태에서 잠시 기다려주세요.";
		}
		return "아직 수신 조각 없음 · 상대 ON·근처 기기 권한·Bluetooth를 확인해주세요.";
	}

	public void fail(String message) {
		close();
		listener.onStatus(message);
	}

	public void close() {
		generation++;
		running = false;
		try {
			if (scanner != null && scanCallback != null) {
				scanner.stopScan(scanCallback);
			}
		} catch (SecurityException ignored) {
			// Permission revocation also invalidates the local session.
		} catch (RuntimeException ignored) {
			// Bluetooth may already be disabled; invalidate callbacks regardless.
		}
		if (legacyLane != null) {
			legacyLane.stop();
		}
		if (extendedLane != null) {
			extendedLane.stop();
		}
		legacyLane = null;
		extendedLane = null;
		scanCallback = null;
		outbox = null;
		seen.clear();
		transmittedMessages.clear();
		listener.onClosed();
	}

}
