package com.e106.keyring.lab;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.Consumer;

import com.e106.keyring.core.AdvertisementCodec;
import com.e106.keyring.core.Encounter;
import com.e106.keyring.core.SavedEncounter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Observes the service while visible; leaving or recreating this screen does not stop an active radio. */
public final class MainActivity extends Activity {
	private static final int GREEN = Color.rgb(23, 107, 86);
	private static final int INK = Color.rgb(29, 46, 40);
	private static final int MUTED = Color.rgb(95, 111, 101);
	private LabController controller;
	private KeyringService service;
	private LabController.Snapshot model;
	private LinearLayout content;
	private ScrollView scroll;
	private TextView status;
	private TextView identity;
	private int selectedTab;
	private boolean bound;
	private boolean visible;
	private boolean pendingStart;
	private Uri pendingExport;
	private final Button[] tabButtons = new Button[3];
	private final Consumer<LabController.Snapshot> observer = snapshot -> {
		model = snapshot;
		render();
	};
	private final ServiceConnection connection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder binder) {
			service = ((KeyringService.LocalBinder)binder).service();
			controller = service.controller();
			if (visible) {
				service.observe(observer);
			}
			controller.refresh();
			if (pendingExport != null) {
				controller.export(pendingExport);
				pendingExport = null;
			}
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			service = null;
			controller = null;
			model = null;
			content.removeAllViews();
			status.setText("서비스 연결 대기 · 기존 기록은 보존됩니다");
		}
	};

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		if (state != null) {
			selectedTab = state.getInt("tab", 0);
			pendingStart = state.getBoolean("pendingStart", false);
		}
		buildLayout();
		bound = bindService(new Intent(this, KeyringService.class), connection, Context.BIND_AUTO_CREATE);
		if (!bound) {
			status.setText("서비스를 시작하지 못했습니다. 앱을 다시 열어주세요.");
		}
	}

	private void buildLayout() {
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(dp(20), dp(16), dp(20), dp(12));
		root.setOnApplyWindowInsetsListener((view, insets) -> {
			if (Build.VERSION.SDK_INT >= 30) {
				android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
				view.setPadding(dp(20) + bars.left, dp(16) + bars.top, dp(20) + bars.right, dp(12) + bars.bottom);
			} else {
				view.setPadding(dp(20), dp(16) + insets.getSystemWindowInsetTop(), dp(20),
					dp(12) + insets.getSystemWindowInsetBottom());
			}
			return insets;
		});
		root.addView(text("SUDDENLY I SEE  /  IoT LAB", 11, GREEN, true));
		TextView title = text("가상 키링", 29, INK, true);
		title.setPadding(0, dp(6), 0, dp(6));
		root.addView(title);
		identity = text("저장소를 확인하고 있습니다", 12, MUTED, false);
		root.addView(identity);
		status = text("준비 중", 14, GREEN, true);
		status.setPadding(dp(14), dp(12), dp(14), dp(12));
		status.setBackground(background(Color.rgb(224, 237, 228), 14));
		LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
		statusParams.setMargins(0, dp(16), 0, dp(12));
		root.addView(status, statusParams);
		LinearLayout tabs = new LinearLayout(this);
		String[] names = {"탐색·시험", "만남 기록", "BLE 진단"};
		for (int index = 0; index < names.length; index++) {
			int tab = index;
			Button button = button(names[index], false, () -> {
				selectedTab = tab;
				scroll.scrollTo(0, 0);
				render();
			});
			tabs.addView(button, new LinearLayout.LayoutParams(0, dp(50), 1));
			tabButtons[index] = button;
		}
		root.addView(tabs);
		scroll = new ScrollView(this);
		scroll.setFillViewport(true);
		FrameLayout frame = new FrameLayout(this);
		content = new LinearLayout(this);
		content.setOrientation(LinearLayout.VERTICAL);
		int width = getResources().getDisplayMetrics().widthPixels;
		FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(Math.min(width - dp(40), dp(720)), -2);
		pageParams.gravity = Gravity.CENTER_HORIZONTAL;
		frame.addView(content, pageParams);
		scroll.addView(frame);
		root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
		setContentView(root);
	}

	private void render() {
		if (model == null) {
			return;
		}
		int previousScroll = scroll.getScrollY();
		content.removeAllViews();
		for (int index = 0; index < tabButtons.length; index++) {
			boolean selected = index == selectedTab;
			tabButtons[index].setBackground(background(selected ? GREEN : Color.rgb(233, 241, 234), 12));
			tabButtons[index].setTextColor(selected ? Color.WHITE : GREEN);
			tabButtons[index].setSelected(selected);
		}
		status.setText(model.status);
		identity.setText(model.local == null ? "저장소 확인 필요"
			: "내 시험 기기 " + model.local.deviceId.toString().substring(0, 8) + "  ·  독립 판정 / v0.13.0");
		if (!model.error.isEmpty()) {
			LinearLayout warning = card("확인이 필요해요", model.error);
			warning.setBackground(background(Color.rgb(255, 237, 218), 18));
		}
		if (selectedTab == 0) {
			renderConnection();
		} else if (selectedTab == 1) {
			renderRecords();
		} else {
			renderDiagnostics();
		}
		scroll.post(() -> scroll.scrollTo(0, previousScroll));
	}

	private void renderConnection() {
		LinearLayout setup = card("01  시험 준비", "두 기기에 같은 APK를 설치하세요."
			+ "\n광고·스캔을 시작하면 화면을 끄거나 다른 앱을 열어도 자동 기록을 계속합니다.");
		addButton(setup, "권한 허용", false, this::requestBlePermissions, true);
		addButton(setup, "Bluetooth 켜기", false, this::enableBluetooth, true);
		boolean prepared = model.local != null && model.local.ready;
		TextView readyText = text(prepared ? "● 내 시험 설정 저장됨 · 버전 " + model.local.settingsVersion
			: "○ ON을 누르면 임시 성향·기기 시각으로 준비", 13, prepared ? GREEN : MUTED, false);
		setup.addView(readyText);
		setup.addView(text("ON을 누르면 임시 성향 데이터를 저장하고 기기 시각으로 준비합니다.\n서비스 등록·동기화는 생략합니다.",
			12, MUTED, false));
		LinearLayout group = card("02  시험 그룹", model.groupCode.isEmpty()
			? "첫 기기에서 새 코드를 만들고 다른 기기에 같은 코드를 입력하세요."
			: "그룹 설정됨 · 끝 4자리 " + model.groupCode.substring(28) + "\n양쪽의 전체 코드가 같아야 통신합니다.");
		addButton(group, "그룹 코드 확인·입력", false, this::editGroup, true);
		LinearLayout connection = card("03  광고·스캔", "양쪽에서 시작 버튼을 누르세요. 같은 그룹의 키링만 표시됩니다."
			+ "\n완전한 상대 데이터를 받으면 즉시 판정·저장합니다. 조각 수신 시간은 BLE 환경에 따라 달라집니다.");
		addButton(connection, "광고·스캔 시작", true, this::startRadio, !model.active);
		if (model.candidates.isEmpty() && !model.connected) {
			connection.addView(text("검색된 기기가 여기 표시됩니다.", 12, MUTED, false));
		}
		for (Map.Entry<String, String> candidate : model.candidates.entrySet()) {
			addButton(connection, candidate.getValue() + " 선택", false,
				() -> controller.select(candidate.getKey()), true);
		}
		if (model.remote != null) {
			connection.addView(text("상대 " + model.remote.deviceId.toString().substring(0, 8)
				+ (model.remote.ready ? " · 시험 설정 준비됨" : " · 시험 설정 적용 대기"), 13, GREEN, true));
		}
		connection.addView(text(model.counters, 12, MUTED, false));
		connection.addView(text(model.receptionStatus, 13, GREEN, true));
		connection.addView(text(model.automaticStatus, 13, GREEN, true));
		addButton(connection, "광고·스캔 중지", false, () -> service.stopRadio(), model.active);
		connection.addView(text("상태 알림의 중지 버튼으로도 종료할 수 있습니다."
			+ "\n오래 시험할 때는 BLE 진단에서 배터리 최적화 설정을 확인하세요.", 12, MUTED, false));
		LinearLayout test = card("04  흐름 확인", "내 정보·설정·성향 광고와 수신 후 독립 판정·저장 과정을 확인하세요.");

		addButton(test, "실패 작업만 즉시 재시도", false, () -> controller.retry(), model.active);
		test.addView(text("같은 상대의 처리 중인 만남은 OFF·재실행 뒤에도 기존 기록을 유지합니다."
			+ "\n미완료 저장은 기존 ID로 복구하며, 서버 전송 없이 원본을 보관합니다.",
			12, MUTED, false));
		renderLog();
	}

	private void renderRecords() {
		LinearLayout summary = card("만남 기록  " + model.records.size(),
			"앱을 종료해도 기록이 남습니다.\n0.13부터 각 기기가 독립 저장합니다. 이벤트 ID·시각은 다를 수 있고 상대 저장은 확인하지 않습니다.");
		addButton(summary, "기록 JSON 내보내기", true, this::chooseExport, model.local != null);
		if (model.records.isEmpty()) {
			card("아직 시험 만남이 없어요", "양쪽에서 같은 그룹 코드·시험 설정을 적용하고 광고·스캔을 시작하세요."
				+ "\n서로 발견하면 자동 저장됩니다. 저장 버튼을 누르지 않아도 됩니다.");
		}
		for (SavedEncounter record : model.records) {
			Encounter event = record.encounter;
			String label = "준비 기록 저장 · 합의 대기";
			if (record.phase == SavedEncounter.Phase.COMMITTED) {
				label = "내 확정 기록 저장 · 상대 확인 대기";
			} else if (record.phase == SavedEncounter.Phase.CONFIRMED) {
				label = "양쪽 저장 확인";
			}
			String heading = event.independent ? "내 기기 저장 완료 · 상대 저장 미확인"
				: "이전 통신 시험 · " + label;
			LinearLayout entry = card(heading,
				DateTimeFormatter.ofPattern("yyyy.MM.dd  HH:mm:ss").withZone(ZoneId.systemDefault())
					.format(Instant.ofEpochMilli(event.occurredAt)) + " · 기기 현지 시간");
			TextView details = text("이벤트 ID\n" + event.eventId + "\n\n기기 A / B\n"
				+ event.deviceA.toString().substring(0, 8) + " / " + event.deviceB.toString().substring(0, 8)
				+ "\n\n내 제출 ID\n" + record.submissionId + "\n\n시험 설정 버전 A / B\n"
				+ event.versionA + " / " + event.versionB + "\n\n서버 제출: 서비스 앱 전송 대기"
				+ " (시험 증빙, 운영 업로드 불가)", 12, MUTED, false);
			details.setTextIsSelectable(true);
			entry.addView(details);
		}
	}

	private void renderDiagnostics() {
		LinearLayout reception = card("실시간 수신 진단", model.receptionStatus + "\n\n" + model.counters);
		addButton(reception, "수신 진단 복사", false, () -> {
			String report = "가상 키링 0.13.0-lab · " + Instant.now() + "\n" + model.receptionStatus
				+ "\n" + model.counters + "\n" + String.join("\n", model.diagnostics);
			getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("수신 진단", report));
			Toast.makeText(this, "그룹 코드 없이 수신 진단을 복사했어요.", Toast.LENGTH_SHORT).show();
		}, true);
		LinearLayout diagnostic = card("이 기기의 BLE", String.join("\n\n", model.diagnostics));
		addButton(diagnostic, "다시 진단", true, () -> controller.refresh(), true);
		addButton(diagnostic, "권한 허용", false, this::requestBlePermissions, true);
		addButton(diagnostic, "상태 알림 설정", false, () -> startActivity(
			new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
				.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())), true);
		addButton(diagnostic, "배터리 최적화 설정", false,
			() -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)), true);
		addButton(diagnostic, "앱 권한 설정 열기", false, () -> startActivity(new Intent(
			Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))), true);
		addButton(diagnostic, "Bluetooth 설정", false,
			() -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)), true);
		addButton(diagnostic, "날짜 및 시간 설정", false,
			() -> startActivity(new Intent(Settings.ACTION_DATE_SETTINGS)), true);
		if (Build.VERSION.SDK_INT < 31) {
			addButton(diagnostic, "위치 서비스 설정", false,
				() -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)), true);
		}
		card("이번 시험의 범위", "양쪽에서 광고·스캔을 병행하며 직접 연결 없이 데이터를 교환합니다."
			+ "\n\n같은 그룹 코드를 가진 앱만 내용을 해석하고 각자 판정합니다. 광고 전파 자체는 주변에 도달합니다."
			+ "\n\n보드 펌웨어, 거리 정확도, 배터리, AI 매칭, 운영 기기 인증·서버 연동은 후속 검증입니다."
			+ "\n\n중지 전까지 화면 꺼짐·다른 앱 사용 중에도 통신합니다. 전력 소모가 늘어날 수 있습니다."
			+ "\n\n장시간 시험은 배터리 최적화에서 이 앱을 제외하고, 삼성 기기의 절전·초절전 앱에서도 제외하세요."
			+ " 강제 종료·재부팅·시스템의 프로세스 종료 후에는 앱을 열고 시험 설정 적용과 시작을 다시 눌러주세요."
			+ " 기존 기록은 유지됩니다.");
		renderLog();
	}

	private void renderLog() {
		LinearLayout log = card("최근 동작", "최근 50개 · 서비스 종료 시 동작 로그는 초기화됩니다.");
		TextView text = text(String.join("\n\n", model.logs), 12, MUTED, false);
		text.setTextIsSelectable(true);
		log.addView(text);
	}

	private void editGroup() {
		EditText input = new EditText(this);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		input.setSingleLine(true);
		input.setText(model.groupCode);
		input.setHint("다른 기기의 32자리 그룹 코드");
		input.setTextSize(14);
		input.setPadding(dp(20), dp(16), dp(20), dp(16));
		AlertDialog dialog = new AlertDialog.Builder(this).setTitle("같은 시험 그룹 설정")
			.setMessage("첫 기기에서 새 코드 만들기를 누르세요. 코드를 길게 눌러 복사할 수 있습니다."
				+ " 다른 기기에는 같은 전체 코드를 입력하세요. 저장하면 탐색이 중지됩니다.")
			.setView(input).setPositiveButton("저장", (view, which) -> controller.setGroup(input.getText().toString()))
			.setNegativeButton("취소", null).setNeutralButton("새 코드 만들기", null).create();
		dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
			.setOnClickListener(view -> input.setText(AdvertisementCodec.newGroup())));
		dialog.show();
	}

	private LinearLayout card(String title, String description) {
		LinearLayout card = new LinearLayout(this);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setPadding(dp(18), dp(18), dp(18), dp(18));
		card.setBackground(background(Color.WHITE, 18));
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
		params.setMargins(0, dp(12), 0, 0);
		content.addView(card, params);
		card.addView(text(title, 18, INK, true));
		TextView body = text(description, 13, MUTED, false);
		body.setPadding(0, dp(10), 0, dp(8));
		body.setLineSpacing(dp(3), 1);
		card.addView(body);
		return card;
	}

	private TextView text(String value, int size, int color, boolean bold) {
		TextView text = new TextView(this);
		text.setText(value);
		text.setTextSize(size);
		text.setTextColor(color);
		if (bold) {
			text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		}
		return text;
	}

	private Button button(String value, boolean primary, Runnable action) {
		Button button = new Button(this);
		button.setText(value);
		button.setTextSize(14);
		button.setAllCaps(false);
		button.setTextColor(primary ? Color.WHITE : GREEN);
		button.setBackground(background(primary ? GREEN : Color.rgb(233, 241, 234), 12));
		button.setPadding(dp(10), dp(8), dp(10), dp(8));
		button.setOnClickListener(view -> action.run());
		return button;
	}

	private void addButton(LinearLayout parent, String label, boolean primary, Runnable action, boolean enabled) {
		Button button = button(label, primary, action);
		button.setEnabled(enabled);
		button.setAlpha(enabled ? 1 : 0.45f);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
		params.setMargins(0, dp(6), 0, dp(6));
		button.setMinHeight(dp(50));
		parent.addView(button, params);
	}

	private GradientDrawable background(int color, int radius) {
		GradientDrawable background = new GradientDrawable();
		background.setColor(color);
		background.setCornerRadius(dp(radius));
		return background;
	}

	private int dp(int value) {
		return Math.round(value * getResources().getDisplayMetrics().density);
	}

	private void requestBlePermissions() {
		if (BleDiagnostics.hasPermissions(this)) {
			controller.refresh();
		} else {
			requestPermissions(BleDiagnostics.permissions(), 1);
		}
	}

	private void startRadio() {
		if (!BleDiagnostics.hasPermissions(this)) {
			requestBlePermissions();
			return;
		}
		if (Build.VERSION.SDK_INT >= 33
			&& checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			pendingStart = true;
			requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 4);
			return;
		}
		startRadioService();
	}

	private void startRadioService() {
		try {
			startForegroundService(new Intent(this, KeyringService.class).setAction(KeyringService.ACTION_START));
		} catch (RuntimeException exception) {
			if (controller != null) {
				controller.reportFailure("백그라운드 실행을 시작하지 못했습니다. 앱에서 권한을 확인한 뒤 다시 시작하세요.");
			}
		}
	}

	@SuppressWarnings("deprecation")
	private void enableBluetooth() {
		if (!BleDiagnostics.hasPermissions(this)) {
			requestBlePermissions();
			return;
		}
		try {
			startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2);
		} catch (SecurityException exception) {
			requestBlePermissions();
		} catch (android.content.ActivityNotFoundException exception) {
			startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
		}
	}

	@SuppressWarnings("deprecation")
	private void chooseExport() {
		Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("application/json");
		intent.putExtra(Intent.EXTRA_TITLE, "keyring-lab-" + System.currentTimeMillis() + ".json");
		startActivityForResult(intent, 3);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == 3 && resultCode == RESULT_OK && data != null && data.getData() != null) {
			if (controller == null) {
				pendingExport = data.getData();
			} else {
				controller.export(data.getData());
			}
		} else if (controller != null) {
			controller.refresh();
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
		super.onRequestPermissionsResult(requestCode, permissions, results);
		if (controller != null) {
			controller.refresh();
		}
		if (requestCode == 4 && pendingStart) {
			pendingStart = false;
			// Notification permission is optional for a foreground service; diagnostics explain the hidden alert.
			startRadioService();
		}
	}

	@Override
	protected void onStart() {
		super.onStart();
		visible = true;
		if (service != null) {
			service.observe(observer);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (controller != null) {
			controller.refresh();
		}
	}

	@Override
	protected void onStop() {
		visible = false;
		if (service != null) {
			service.removeObserver(observer);
		}
		super.onStop();
	}

	@Override
	protected void onSaveInstanceState(Bundle state) {
		state.putInt("tab", selectedTab);
		state.putBoolean("pendingStart", pendingStart);
		super.onSaveInstanceState(state);
	}

	@Override
	protected void onDestroy() {
		if (service != null) {
			service.removeObserver(observer);
		}
		if (bound) {
			unbindService(connection);
		}
		super.onDestroy();
	}
}
