package com.e106.keyring.lab;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.e106.keyring.core.KeyringMotion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/** Owns the radio and storage independently of the activity. All service state is main-thread confined. */
public final class KeyringService extends Service {
	public static final String ACTION_START = "com.e106.keyring.lab.START";
	public static final String ACTION_STOP = "com.e106.keyring.lab.STOP";
	private static final String CHANNEL = "keyring-radio";
	private static final int NOTIFICATION_ID = 106;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final LocalBinder binder = new LocalBinder();
	private final Set<Consumer<LabController.Snapshot>> observers = new HashSet<>();
	private final Runnable renewWakeLock = this::holdCpu;
	private LabController controller;
	private LabController.Snapshot latest;
	private PowerManager.WakeLock wakeLock;
	private boolean foreground;
	private boolean starting;
	private boolean destroyed;
	private int startGeneration;
	private String notificationText = "";
	private final Set<UUID> consumedFeedback = new HashSet<>();
	private final Queue<UUID> pendingFeedback = new ArrayDeque<>();
	private UUID activeFeedback;
	private int feedbackGeneration;
	private long feedbackStartedAt;
	private boolean feedbackPlaying;
	private Vibrator vibrator;
	private final Runnable nextFeedback = () -> {
		if (activeFeedback != null) {
			controller.finishFeedback(activeFeedback, "FINISHED");
		}
		activeFeedback = null;
		feedbackPlaying = false;
		playFeedback();
	};
	private final BroadcastReceiver radioReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
			if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
				stopRadio();
			} else if (LocationManager.PROVIDERS_CHANGED_ACTION.equals(intent.getAction())) {
				controller.refresh();
			}
		}
	};

	public final class LocalBinder extends Binder {
		public KeyringService service() {
			return KeyringService.this;
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		NotificationChannel channel = new NotificationChannel(CHANNEL, "키링 광고·스캔", NotificationManager.IMPORTANCE_LOW);
		channel.setDescription("백그라운드 통신 상태와 중지 버튼");
		getSystemService(NotificationManager.class).createNotificationChannel(channel);
		controller = new LabController(this, this::onSnapshot);
		IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
		if (Build.VERSION.SDK_INT < 31) {
			filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
		}
		if (Build.VERSION.SDK_INT >= 33) {
			registerReceiver(radioReceiver, filter, Context.RECEIVER_EXPORTED);
		} else {
			registerReceiver(radioReceiver, filter);
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	public LabController controller() {
		return controller;
	}

	public void observe(Consumer<LabController.Snapshot> observer) {
		observers.add(observer);
		if (latest != null) {
			observer.accept(latest);
		}
	}

	public void removeObserver(Consumer<LabController.Snapshot> observer) {
		observers.remove(observer);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && ACTION_START.equals(intent.getAction())) {
			startRadio();
		} else {
			stopRadio();
		}
		// Process death requires explicit time/settings preparation again; never restart a fresh match silently.
		return START_NOT_STICKY;
	}

	private void startRadio() {
		if (foreground) {
			return;
		}
		int generation = ++startGeneration;
		starting = true;
		try {
			Notification notification = notification("광고·스캔을 준비하고 있습니다");
			if (Build.VERSION.SDK_INT >= 29) {
				int type = Build.VERSION.SDK_INT >= 31 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
					: ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
				startForeground(NOTIFICATION_ID, notification, type);
			} else {
				startForeground(NOTIFICATION_ID, notification);
			}
			foreground = true;
			holdCpu();
			if (!foreground) {
				return;
			}
			controller.start(active -> {
				if (destroyed || generation != startGeneration) {
					return;
				}
				starting = false;
				if (!active || (latest != null && !latest.active)) {
					finishForeground();
				}
			});
		} catch (RuntimeException exception) {
			stopRadio();
			controller.reportFailure("백그라운드 실행을 시작하지 못했습니다. 앱을 열고 BLE 권한을 확인한 뒤 다시 시작하세요.");
		}
	}

	public void stopRadio() {
		startGeneration++;
		starting = false;
		controller.stop();
		finishForeground();
	}

	@Override
	public void onTaskRemoved(Intent rootIntent) {
		// Removing the app from Recents is an explicit exit; Home/screen-off still keep the service alive.
		stopRadio();
		super.onTaskRemoved(rootIntent);
	}

	private void onSnapshot(LabController.Snapshot snapshot) {
		if (destroyed) {
			return;
		}
		latest = snapshot;
		if (foreground && snapshot.active) {
			for (UUID event : snapshot.feedbackRequests) {
				if (consumedFeedback.add(event)) {
					pendingFeedback.add(event);
				}
			}
			playFeedback();
		}
		snapshot.feedbackStartedAt = feedbackStartedAt;
		if (foreground && !starting && !snapshot.active) {
			finishForeground();
		} else if (foreground && snapshot.active) {
			String text = (snapshot.running ? "광고·스캔 중" : "광고 시작 대기")
				+ " · 만남 기록 " + snapshot.records.size() + "개";
			if (!text.equals(notificationText)) {
				getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
			}
		}
		for (Consumer<LabController.Snapshot> observer : new HashSet<>(observers)) {
			observer.accept(snapshot);
		}
	}

	private void playFeedback() {
		if (!foreground || feedbackPlaying || pendingFeedback.isEmpty()) {
			return;
		}
		feedbackPlaying = true;
		activeFeedback = pendingFeedback.remove();
		UUID event = activeFeedback;
		int generation = feedbackGeneration;
		controller.beginFeedback(event, allowed -> {
			if (generation != feedbackGeneration || !foreground || !event.equals(activeFeedback)) {
				return;
			}
			if (!allowed) {
				activeFeedback = null;
				feedbackPlaying = false;
				playFeedback();
				return;
			}
			actuateFeedback(event);
		});
	}

	@SuppressWarnings("deprecation")
	private void actuateFeedback(UUID event) {
		feedbackStartedAt = SystemClock.elapsedRealtime();
		if (vibrator == null) {
			vibrator = Build.VERSION.SDK_INT >= 31 ? getSystemService(VibratorManager.class).getDefaultVibrator()
				: (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
		}
		if (vibrator != null && vibrator.hasVibrator()) {
			AudioAttributes attributes = new AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_NOTIFICATION).build();
			try {
				vibrator.vibrate(VibrationEffect.createWaveform(KeyringMotion.vibrationPattern(),
					new int[] {0, 255, 0, 255}, -1), attributes);
			} catch (RuntimeException exception) {
				controller.reportFailure("진동을 실행하지 못했어요. 통신과 만남 기록은 유지됩니다.");
			}
		} else {
			controller.noVibrator(event);
		}
		publishFeedback();
		main.postDelayed(nextFeedback, KeyringMotion.FEEDBACK_MS);
	}

	/** Cancels this local two-pulse group without changing matching, radio or encounter state. */
	public void stopFeedback() {
		feedbackGeneration++;
		main.removeCallbacks(nextFeedback);
		if (activeFeedback != null) {
			controller.finishFeedback(activeFeedback, "STOPPED");
		}
		for (UUID event : pendingFeedback) {
			controller.finishFeedback(event, "STOPPED");
		}
		pendingFeedback.clear();
		activeFeedback = null;
		feedbackStartedAt = 0;
		feedbackPlaying = false;
		if (vibrator != null) {
			vibrator.cancel();
		}
		publishFeedback();
	}

	private void publishFeedback() {
		if (latest != null) {
			latest.feedbackStartedAt = feedbackStartedAt;
			for (Consumer<LabController.Snapshot> observer : new HashSet<>(observers)) {
				observer.accept(latest);
			}
		}
	}

	private Notification notification(String text) {
		notificationText = text;
		Intent open = new Intent(this, HomeActivity.class)
			.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
		PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);
		PendingIntent stop = PendingIntent.getService(this, 1,
			new Intent(this, KeyringService.class).setAction(ACTION_STOP), flags);
		Notification.Builder builder = new Notification.Builder(this, CHANNEL)
			.setSmallIcon(R.drawable.ic_keyring_status).setContentTitle("가상 키링 실행 중")
			.setContentText(text).setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
			.setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE)
			.setVisibility(Notification.VISIBILITY_PRIVATE)
			.addAction(new Notification.Action.Builder(null, "중지", stop).build());
		if (Build.VERSION.SDK_INT >= 31) {
			builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
		}
		return builder.build();
	}

	private void holdCpu() {
		if (!foreground) {
			return;
		}
		try {
			if (wakeLock == null) {
				wakeLock = getSystemService(PowerManager.class)
					.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "keyring:ble-radio");
				wakeLock.setReferenceCounted(false);
			}
			// Fragmented advertisements need periodic CPU work with the screen off. Bound each lease.
			wakeLock.acquire(10 * 60 * 1000L);
			main.removeCallbacks(renewWakeLock);
			main.postDelayed(renewWakeLock, 5 * 60 * 1000L);
		} catch (RuntimeException exception) {
			stopRadio();
			controller.reportFailure("화면 꺼짐 중 통신을 준비하지 못했습니다. 다시 시작해주세요.");
		}
	}

	private void finishForeground() {
		foreground = false;
		stopFeedback();
		main.removeCallbacks(renewWakeLock);
		if (wakeLock != null && wakeLock.isHeld()) {
			wakeLock.release();
		}
		stopForeground(STOP_FOREGROUND_REMOVE);
		stopSelf();
	}

	@Override
	public void onDestroy() {
		destroyed = true;
		unregisterReceiver(radioReceiver);
		observers.clear();
		controller.dispose();
		finishForeground();
		super.onDestroy();
	}
}
