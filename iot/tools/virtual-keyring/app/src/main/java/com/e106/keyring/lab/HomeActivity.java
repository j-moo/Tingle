package com.e106.keyring.lab;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

import com.e106.keyring.core.AdvertisementCodec;
import com.e106.keyring.core.SavedEncounter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** The keyring is a view of the service: changing screens never owns or restarts the radio. */
public final class HomeActivity extends Activity {
	private static final int ORANGE = Color.rgb(244, 133, 64);
	private KeyringService service;
	private LabController controller;
	private LabController.Snapshot model;
	private KeyringView keyring;
	private LinearLayout page;
	private LinearLayout trafficContent;
	private ScrollView trafficScroll;
	private TextView statusText;
	private TextView detailText;
	private TextView receptionText;
	private Button powerButton;
	private Button shareButton;
	private Button downloadButton;
	private boolean dark;
	private boolean showingHistory;
	private boolean bound;
	private boolean visible;
	private boolean resumed;
	private boolean pendingStart;
	private Uri pendingExport;
	private long renderedTrafficId = -1;
	private int renderedTrafficCount = -1;
	private android.window.OnBackInvokedCallback backCallback;
	private String lastShownError = "";
	private final Consumer<LabController.Snapshot> observer = snapshot -> {
		model = snapshot;
		if (showingHistory && !snapshot.error.isEmpty() && !snapshot.error.equals(lastShownError)) {
			Toast.makeText(this, snapshot.error, Toast.LENGTH_LONG).show();
		}
		lastShownError = snapshot.error;
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
			if (pendingExport != null) {
				exportReceived(pendingExport);
				pendingExport = null;
			}
			controller.refresh();
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			service = null;
			controller = null;
			model = null;
			render();
		}
	};

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		dark = getPreferences(MODE_PRIVATE).getBoolean("dark", true);
		if (state != null) {
			showingHistory = state.getBoolean("history");
			pendingStart = state.getBoolean("pendingStart");
			String savedUri = state.getString("pendingExport");
			pendingExport = savedUri == null ? null : Uri.parse(savedUri);
		}
		buildLayout();
		bound = bindService(new Intent(this, KeyringService.class), connection, Context.BIND_AUTO_CREATE);
	}

	private int ink() {
		return dark ? Color.rgb(242, 241, 237) : Color.rgb(28, 29, 28);
	}

	private int muted() {
		return dark ? Color.rgb(151, 151, 148) : Color.rgb(108, 110, 106);
	}

	@SuppressWarnings("deprecation")
	private void buildLayout() {
		if (keyring != null) {
			keyring.pauseMotion();
		}
		keyring = null;
		page = new LinearLayout(this);
		page.setOrientation(LinearLayout.VERTICAL);
		page.setBackgroundColor(dark ? Color.BLACK : Color.WHITE);
		page.setOnApplyWindowInsetsListener((view, insets) -> {
			if (Build.VERSION.SDK_INT >= 30) {
				android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
				view.setPadding(dp(24) + bars.left, dp(12) + bars.top, dp(24) + bars.right, dp(10) + bars.bottom);
			} else {
				view.setPadding(dp(24), dp(12) + insets.getSystemWindowInsetTop(), dp(24),
					dp(10) + insets.getSystemWindowInsetBottom());
			}
			return insets;
		});
		getWindow().getDecorView().setSystemUiVisibility(dark ? 0
			: View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
		getWindow().setStatusBarColor(dark ? Color.BLACK : Color.WHITE);
		getWindow().setNavigationBarColor(dark ? Color.BLACK : Color.WHITE);
		if (Build.VERSION.SDK_INT >= 29) {
			getWindow().setNavigationBarContrastEnforced(false);
		}
		if (showingHistory) {
			buildHistory();
		} else {
			buildHome();
		}
		setContentView(page);
		if (Build.VERSION.SDK_INT >= 33) {
			if (backCallback != null) {
				getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
				backCallback = null;
			}
			backCallback = this::goBack;
			getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
				android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
		}
		renderedTrafficId = -1;
		render();
		if (resumed && keyring != null) {
			keyring.resumeMotion();
		}
	}

	private void buildHome() {
		FrameLayout header = new FrameLayout(this);
		LinearLayout brand = new LinearLayout(this);
		brand.setOrientation(LinearLayout.VERTICAL);
		TextView wordmark = label("SUDDENLY I SEE", 12, ink(), true);
		wordmark.setLetterSpacing(0.16f);
		brand.addView(wordmark);
		TextView subtitle = label("VIRTUAL KEYRING", 10, muted(), false);
		subtitle.setLetterSpacing(0.12f);
		subtitle.setPadding(0, dp(9), 0, 0);
		brand.addView(subtitle);
		FrameLayout.LayoutParams brandParams = new FrameLayout.LayoutParams(-2, -2);
		brandParams.topMargin = dp(10);
		header.addView(brand, brandParams);
		FrameLayout.LayoutParams themeParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END);
		header.addView(themeButton(), themeParams);
		FrameLayout.LayoutParams historyParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END);
		historyParams.topMargin = dp(60);
		header.addView(icon(R.drawable.ic_history, "송수신 기록 열기", () -> {
			showingHistory = true;
			buildLayout();
		}), historyParams);
		page.addView(header, new LinearLayout.LayoutParams(-1, dp(112)));
		keyring = new KeyringView(this);
		keyring.setDark(dark);
		keyring.setContentDescription("키링, 누르면 현재 LED·진동 안내 중지");
		keyring.setOnClickListener(view -> {
			if (service != null) {
				service.stopFeedback();
			}
		});
		page.addView(keyring, new LinearLayout.LayoutParams(-1, 0, 1));
		statusText = label("키링이 쉬고 있어요", 16, ink(), true);
		statusText.setGravity(Gravity.CENTER);
		page.addView(statusText, new LinearLayout.LayoutParams(-1, -2));
		detailText = label("ON을 눌러 주변 키링과 만나보세요", 12, muted(), false);
		detailText.setGravity(Gravity.CENTER);
		detailText.setPadding(0, dp(10), 0, dp(24));
		page.addView(detailText, new LinearLayout.LayoutParams(-1, -2));
		powerButton = button("ON", this::togglePower);
		powerButton.setTextSize(18);
		powerButton.setLetterSpacing(0.12f);
		LinearLayout.LayoutParams powerParams = new LinearLayout.LayoutParams(
			Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(360)), dp(64));
		powerParams.gravity = Gravity.CENTER_HORIZONTAL;
		page.addView(powerButton, powerParams);
		Button silence = button("현재 LED·진동 중지", () -> {
			if (service != null) {
				service.stopFeedback();
			}
		});
		silence.setTextSize(12);
		silence.setBackgroundColor(Color.TRANSPARENT);
		page.addView(silence, new LinearLayout.LayoutParams(-1, dp(40)));
		Button settings = button("설정 · BLE 진단", () -> startActivity(new Intent(this, MainActivity.class)));
		settings.setTextSize(12);
		settings.setTextColor(muted());
		settings.setBackgroundColor(Color.TRANSPARENT);
		LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(-2, dp(48));
		settingsParams.gravity = Gravity.CENTER_HORIZONTAL;
		settingsParams.topMargin = dp(8);
		page.addView(settings, settingsParams);
	}

	private void buildHistory() {
		LinearLayout header = new LinearLayout(this);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.addView(icon(R.drawable.ic_back, "키링으로 돌아가기", () -> {
			showingHistory = false;
			buildLayout();
		}), new LinearLayout.LayoutParams(dp(48), dp(48)));
		TextView title = label("송수신 기록", 22, ink(), true);
		title.setPadding(dp(16), 0, 0, 0);
		header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
		header.addView(themeButton(), new LinearLayout.LayoutParams(dp(48), dp(48)));
		page.addView(header);
		TextView hint = label("수신은 왼쪽, 송신은 오른쪽, 처리 과정은 가운데 표시돼요.\n임시 성향 · 항상 성공하는 시험 매칭 · 등록·서버 동기화 생략",
			12, muted(), false);
		hint.setPadding(dp(4), dp(18), 0, dp(10));
		page.addView(hint);
		receptionText = label("수신 상태를 확인하고 있어요", 12, muted(), false);
		receptionText.setPadding(dp(4), 0, dp(4), dp(6));
		page.addView(receptionText);
		trafficScroll = new ScrollView(this);
		trafficScroll.setFillViewport(true);
		FrameLayout frame = new FrameLayout(this);
		trafficContent = new LinearLayout(this);
		trafficContent.setOrientation(LinearLayout.VERTICAL);
		trafficContent.setPadding(dp(4), dp(16), dp(4), dp(16));
		FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
			Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(720)), -2, Gravity.CENTER_HORIZONTAL);
		frame.addView(trafficContent, contentParams);
		trafficScroll.addView(frame);
		page.addView(trafficScroll, new LinearLayout.LayoutParams(-1, 0, 1));
		TextView note = label("최근 80개 표시 · 모든 처리 과정은 계속 저장돼요.\nJSON에는 전체 송수신·처리 과정과 수신 데이터를 담아요.",
			11, muted(), false);
		note.setGravity(Gravity.CENTER);
		note.setPadding(0, dp(12), 0, dp(12));
		page.addView(note);
		LinearLayout exports = new LinearLayout(this);
		shareButton = button("JSON 공유", this::shareReceived);
		downloadButton = button("JSON 저장", this::chooseExport);
		LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(52), 1);
		left.setMargins(0, 0, dp(6), 0);
		exports.addView(shareButton, left);
		LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(52), 1);
		right.setMargins(dp(6), 0, 0, 0);
		exports.addView(downloadButton, right);
		page.addView(exports);
	}

	private void render() {
		if (showingHistory) {
			receptionText.setText(model == null ? "수신 상태를 확인하고 있어요" : model.receptionStatus);
			renderTraffic();
			return;
		}
		boolean active = model != null && model.active;
		keyring.update(active, model == null ? 0 : model.feedbackStartedAt);
		powerButton.setText(active ? "OFF" : "ON");
		powerButton.setContentDescription(active ? "OFF, 키링 통신 끄기" : "ON, 키링 통신 켜기");
		powerButton.setEnabled(controller != null && model != null && model.local != null);
		powerButton.setBackground(surface(active ? ORANGE : ink(), 32));
		powerButton.setTextColor(active ? Color.rgb(43, 24, 12) : dark ? Color.BLACK : Color.WHITE);
		if (model == null) {
			statusText.setText("키링을 준비하고 있어요");
			detailText.setText("기존 만남 기록을 확인하고 있습니다");
			return;
		}
		statusText.setText(active ? model.automaticStatus : "키링이 쉬고 있어요");
		statusText.setTextColor(active ? (dark ? ORANGE : Color.rgb(160, 70, 20)) : ink());
		if (!model.error.isEmpty()) {
			detailText.setText(model.error);
		} else if (active) {
			long confirmed = model.records.stream().filter(record -> record.phase == SavedEncounter.Phase.CONFIRMED)
				.count();
			detailText.setText(getString(R.string.radio_summary, confirmed, model.records.size() - confirmed));
		} else {
			detailText.setText(model.groupCode.isEmpty() ? "ON을 눌러 같은 시험 그룹을 설정하세요"
				: "ON을 눌러 주변 키링과 만나보세요");
		}
	}

	private void renderTraffic() {
		boolean available = model != null && controller != null;
		shareButton.setEnabled(available);
		downloadButton.setEnabled(available);
		long newest = model == null || model.traffic.isEmpty() ? 0 : model.traffic.get(0).id;
		int count = model == null ? 0 : model.traffic.size();
		if (newest == renderedTrafficId && count == renderedTrafficCount) {
			return;
		}
		boolean atBottom = trafficContent.getHeight()
			<= trafficScroll.getScrollY() + trafficScroll.getHeight() + dp(80);
		boolean firstRender = renderedTrafficId == -1;
		renderedTrafficId = newest;
		renderedTrafficCount = count;
		trafficContent.removeAllViews();
		if (model != null && model.hasOlderTraffic) {
			trafficContent.addView(button("이전 기록 80개 더 보기", () -> controller.loadOlderTraffic()));
		}
		if (newest == 0) {
			TextView empty = label("아직 나눈 데이터가 없어요.\n두 키링을 ON으로 켜면 이곳에 기록이 쌓여요.",
				14, muted(), false);
			empty.setGravity(Gravity.CENTER);
			empty.setPadding(0, dp(60), 0, 0);
			trafficContent.addView(empty, new LinearLayout.LayoutParams(-1, -2));
			return;
		}
		for (int index = model.traffic.size() - 1; index >= 0; index--) {
			TrafficEntry entry = model.traffic.get(index);
			boolean received = "RX".equals(entry.direction);
			boolean notice = "PROCESS".equals(entry.direction)
				|| "DISCOVERY".equals(entry.direction) || "SAVED".equals(entry.direction);
			int alignment = notice ? Gravity.CENTER_HORIZONTAL : received ? Gravity.START : Gravity.END;
			LinearLayout message = new LinearLayout(this);
			message.setOrientation(LinearLayout.VERTICAL);
			message.setGravity(alignment);
			message.setPadding(received || notice ? 0 : dp(24), dp(13), received && !notice ? dp(24) : 0, dp(13));
			String time = DateTimeFormatter.ofPattern("MM.dd  HH:mm:ss").withZone(ZoneId.systemDefault())
				.format(Instant.ofEpochMilli(entry.timestamp));
			String peer = AdvertisementCodec.BROADCAST.toString().equals(entry.peerId)
				? "같은 그룹" : entry.peerId.substring(0, 8);
			TextView caption = label(entry.categoryName() + "  ·  " + peer + "  ·  " + time, 10, muted(), false);
			caption.setGravity(alignment);
			message.addView(caption);
			TextView body = label(entry.summary(), 14,
				received || notice ? ink() : dark ? ORANGE : Color.rgb(160, 70, 20), notice);
			body.setGravity(alignment);
			body.setPadding(0, dp(7), 0, dp(5));
			message.addView(body);
			String eventId = entry.eventId != null ? entry.eventId
				: entry.packet == null || entry.packet.encounter == null ? null
				: entry.packet.encounter.eventId.toString();
			String event = eventId == null ? "" : " · " + eventId.substring(0, 8);
			TextView type = label(entry.typeName() + event + "  ↗ JSON", 10, muted(), false);
			type.setGravity(alignment);
			message.addView(type);
			message.setOnClickListener(view -> showPacket(entry));
			message.setFocusable(true);
			message.setContentDescription(caption.getText() + " " + entry.summary() + ", JSON 보기");
			trafficContent.addView(message, new LinearLayout.LayoutParams(-1, -2));
		}
		if (atBottom || firstRender) {
			trafficScroll.post(() -> trafficScroll.fullScroll(View.FOCUS_DOWN));
		}
	}

	private void showPacket(TrafficEntry entry) {
		try {
			TextView json = label(entry.toJson().toString(2), 12, ink(), false);
			json.setTypeface(Typeface.MONOSPACE);
			json.setTextIsSelectable(true);
			json.setPadding(dp(20), dp(12), dp(20), dp(16));
			ScrollView container = new ScrollView(this);
			container.addView(json);
			new AlertDialog.Builder(this, dialogTheme()).setTitle(entry.typeName()
				+ " · " + entry.categoryName())
				.setView(container).setPositiveButton("닫기", null).show();
		} catch (org.json.JSONException exception) {
			Toast.makeText(this, "JSON을 표시할 수 없습니다.", Toast.LENGTH_SHORT).show();
		}
	}

	private int dialogTheme() {
		return dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
	}

	private void togglePower() {
		if (controller == null || model == null) {
			return;
		}
		if (model.active) {
			pendingStart = false;
			service.stopRadio();
		} else if (model.groupCode.isEmpty()) {
			editGroup();
		} else {
			startRadio();
		}
	}

	private void editGroup() {
		EditText input = new EditText(this);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		input.setTextColor(ink());
		input.setHintTextColor(muted());
		input.setTextSize(14);
		input.setHint("다른 키링과 같은 32자리 코드");
		input.setPadding(dp(24), dp(16), dp(24), dp(16));
		AlertDialog dialog = new AlertDialog.Builder(this, dialogTheme()).setTitle("같은 시험 그룹으로 만나기")
			.setMessage("첫 기기에서 코드를 만든 뒤 다른 기기에 같은 코드를 입력하세요."
				+ " 저장 후 ON을 누르면 시작됩니다. 코드는 설정에서도 확인할 수 있어요.")
			.setView(input).setPositiveButton("저장", (view, which) -> controller.setGroup(input.getText().toString()))
			.setNegativeButton("취소", null).setNeutralButton("새 코드 만들기", null).create();
		dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
			.setOnClickListener(view -> input.setText(AdvertisementCodec.newGroup())));
		dialog.show();
	}

	@SuppressWarnings("deprecation")
	private void startRadio() {
		if (controller == null || model == null) {
			return;
		}
		pendingStart = true;
		if (!BleDiagnostics.hasPermissions(this)) {
			requestPermissions(BleDiagnostics.permissions(), 1);
			return;
		}
		BluetoothManager manager = getSystemService(BluetoothManager.class);
		if (manager == null || manager.getAdapter() == null) {
			pendingStart = false;
			controller.reportFailure("이 기기는 Bluetooth를 지원하지 않습니다.");
			return;
		}
		if (!manager.getAdapter().isEnabled()) {
			startActivityForResult(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), 2);
			return;
		}
		if (!BleDiagnostics.locationEnabled(this)) {
			pendingStart = false;
			controller.reportFailure("위치 서비스를 켜고 ON을 다시 눌러주세요.");
			startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
			return;
		}
		if (Build.VERSION.SDK_INT >= 33 && !getPreferences(MODE_PRIVATE).getBoolean("askedNotification", false)
			&& checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotification", true).apply();
			requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 4);
			return;
		}
		pendingStart = false;
		try {
			startForegroundService(new Intent(this, KeyringService.class).setAction(KeyringService.ACTION_START));
		} catch (RuntimeException exception) {
			controller.reportFailure("통신을 시작하지 못했습니다. 설정에서 BLE 권한을 확인해주세요.");
		}
	}

	private void shareReceived() {
		controller.shareReceived(uri -> {
			if (isFinishing() || isDestroyed()) {
				return;
			}
			Intent send = new Intent(Intent.ACTION_SEND).setType("application/json")
				.putExtra(Intent.EXTRA_STREAM, uri)
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			send.setClipData(ClipData.newRawUri("받은 키링 데이터", uri));
			try {
				startActivity(Intent.createChooser(send, "받은 JSON 공유"));
			} catch (android.content.ActivityNotFoundException exception) {
				Toast.makeText(this, "공유 앱이 없어요. JSON 저장 버튼을 사용해주세요.", Toast.LENGTH_LONG).show();
			}
		});
	}

	private void exportReceived(Uri uri) {
		controller.exportReceived(uri, message -> Toast.makeText(getApplicationContext(), message,
			Toast.LENGTH_LONG).show());
	}

	@SuppressWarnings("deprecation")
	private void chooseExport() {
		Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
			.setType("application/json")
			.putExtra(Intent.EXTRA_TITLE, "keyring-received-" + System.currentTimeMillis() + ".json");
		startActivityForResult(intent, 3);
	}

	@Override
	protected void onActivityResult(int request, int result, Intent data) {
		super.onActivityResult(request, result, data);
		if (request == 3 && result == RESULT_OK && data != null && data.getData() != null) {
			if (controller == null) {
				pendingExport = data.getData();
			} else {
				exportReceived(data.getData());
			}
		} else if (request == 2 && pendingStart) {
			pendingStart = false;
			if (result == RESULT_OK) {
				startRadio();
			}
		}
	}

	@Override
	public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
		super.onRequestPermissionsResult(request, permissions, results);
		if (!pendingStart) {
			return;
		}
		pendingStart = false;
		if (request == 4 || (request == 1 && BleDiagnostics.hasPermissions(this))) {
			startRadio();
		} else if (controller != null) {
			controller.reportFailure("주변 기기 권한이 필요해요. 설정에서 권한을 허용한 뒤 ON을 눌러주세요.");
		}
	}

	private ImageButton themeButton() {
		return icon(dark ? R.drawable.ic_sun : R.drawable.ic_moon, dark ? "라이트 모드로 전환" : "다크 모드로 전환",
			() -> {
				dark = !dark;
				getPreferences(MODE_PRIVATE).edit().putBoolean("dark", dark).apply();
				buildLayout();
			});
	}

	private ImageButton icon(int resource, String description, Runnable action) {
		ImageButton button = new ImageButton(this);
		button.setImageResource(resource);
		button.setImageTintList(ColorStateList.valueOf(ink()));
		button.setBackground(surface(dark ? Color.rgb(23, 23, 23) : Color.rgb(243, 243, 240), 24));
		button.setPadding(dp(13), dp(13), dp(13), dp(13));
		button.setContentDescription(description);
		button.setOnClickListener(view -> action.run());
		return button;
	}

	private Button button(String title, Runnable action) {
		Button button = new Button(this);
		button.setAllCaps(false);
		button.setText(title);
		button.setTextSize(14);
		button.setTextColor(ink());
		button.setBackground(surface(dark ? Color.rgb(27, 27, 27) : Color.rgb(241, 242, 239), 26));
		button.setOnClickListener(view -> action.run());
		return button;
	}

	private RippleDrawable surface(int color, int radius) {
		GradientDrawable shape = new GradientDrawable();
		shape.setColor(color);
		shape.setCornerRadius(dp(radius));
		return new RippleDrawable(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x22000000), shape, null);
	}

	private TextView label(String text, int size, int color, boolean bold) {
		TextView view = new TextView(this);
		view.setText(text);
		view.setTextSize(size);
		view.setTextColor(color);
		view.setLineSpacing(dp(3), 1);
		view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
		return view;
	}

	private int dp(int value) {
		return Math.round(value * getResources().getDisplayMetrics().density);
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
		resumed = true;
		if (keyring != null) {
			keyring.resumeMotion();
		}
		if (controller != null) {
			controller.refresh();
		}
	}

	@Override
	protected void onPause() {
		resumed = false;
		if (keyring != null) {
			keyring.pauseMotion();
		}
		super.onPause();
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
		state.putBoolean("history", showingHistory);
		state.putBoolean("pendingStart", pendingStart);
		state.putString("pendingExport", pendingExport == null ? null : pendingExport.toString());
		super.onSaveInstanceState(state);
	}

	@Override
	@SuppressWarnings("deprecation")
	@SuppressLint("GestureBackNavigation") // API 26-32 fallback; API 33+ registers OnBackInvokedDispatcher above.
	public void onBackPressed() {
		goBack();
	}

	private void goBack() {
		if (showingHistory) {
			showingHistory = false;
			buildLayout();
		} else {
			if (service != null) {
				service.stopRadio();
			}
			finishAndRemoveTask();
		}
	}

	@Override
	protected void onDestroy() {
		if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
			getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
		}
		if (service != null) {
			service.removeObserver(observer);
		}
		if (bound) {
			unbindService(connection);
		}
		super.onDestroy();
	}
}
