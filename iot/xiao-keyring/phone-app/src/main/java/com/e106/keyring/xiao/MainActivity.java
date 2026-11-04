package com.e106.keyring.xiao;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Standalone Android lab client. Production FE/BE contracts are not silently changed. */
@SuppressLint({"SetTextI18n", "MissingPermission"})
public final class MainActivity extends Activity implements BleClient.Listener {
	private BleClient ble;
	private Outbox outbox;
	private SharedPreferences prefs;
	private TextView status;
	private TextView registry;
	private TextView log;
	private EditText endpoint;
	private LinearLayout devices;
	private final Set<String> found = new HashSet<>();
	private final ExecutorService network = Executors.newSingleThreadExecutor();
	private String deviceId;
	private int revision;
	private boolean transferBusy;

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);
		prefs = getSharedPreferences("devices", MODE_PRIVATE);
		outbox = new Outbox(this);
		ble = new BleClient(this, this);
		ScrollView scroll = new ScrollView(this);
		LinearLayout body = new LinearLayout(this);
		body.setOrientation(LinearLayout.VERTICAL);
		body.setPadding(24, 24, 24, 24);
		scroll.addView(body);
		setContentView(scroll);
		TextView title = new TextView(this);
		title.setText("XIAO 키링 테스트 · 항상 매칭");
		title.setTextSize(24);
		body.addView(title);
		TextView explanation = new TextView(this);
		explanation.setText("가상 데이터 1,024바이트를 사용합니다. 여러 키링을 등록하고 한 대씩 연결합니다. "
			+ "키링 재부팅 후에는 시간·설정을 다시 동기화하세요. 실제 회원 정보나 사진은 입력하지 마세요.");
		body.addView(explanation);
		registry = new TextView(this);
		body.addView(registry);
		updateRegistry();
		button(body, "키링 검색", this::scan);
		devices = new LinearLayout(this);
		devices.setOrientation(LinearLayout.VERTICAL);
		body.addView(devices);
		status = new TextView(this);
		status.setText("연결할 키링을 검색하세요.");
		body.addView(status);
		button(body, "선택한 키링 등록", this::register);
		button(body, "테스트 정보·시간 동기화", this::sync);
		button(body, "기기 상태 새로고침", this::refresh);
		button(body, "확정·서버 검증 큐 내려받기", this::download);
		endpoint = new EditText(this);
		endpoint.setSingleLine();
		endpoint.setHint("테스트 서버 /lab/records URL");
		endpoint.setText(prefs.getString("endpoint", "http://127.0.0.1:8765/lab/records"));
		body.addView(endpoint);
		button(body, "테스트 서버로 업로드 · 저장 ACK 반영", this::upload);
		button(body, "연결 종료", () -> {
			ble.closeConnection();
			disconnected();
		});
		log = new TextView(this);
		body.addView(log);
	}

	private void button(LinearLayout parent, String text, Runnable action) {
		Button button = new Button(this);
		button.setText(text);
		button.setOnClickListener(view -> {
			if (transferBusy) {
				message("현재 동기화가 진행 중입니다.");
			} else {
				action.run();
			}
		});
		parent.addView(button);
	}

	private void message(String text) {
		String previous = log.getText().toString();
		log.setText(text + "\n" + previous.substring(0, Math.min(previous.length(), 6000)));
	}

	private void scan() {
		String[] permissions = Build.VERSION.SDK_INT >= 31
			? new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
			: new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
		for (String permission : permissions) {
			if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
				requestPermissions(permissions, 1);
				message("권한 허용 후 검색을 다시 누르세요.");
				return;
			}
		}
		found.clear();
		devices.removeAllViews();
		ble.scan();
	}

	@Override
	public void found(BluetoothDevice device) {
		if (found.add(device.getAddress())) {
			button(devices, "연결 · " + device.getAddress(), () -> {
				deviceId = null;
				status.setText("연결·페어링 중…");
				ble.connect(device);
			});
		}
	}

	@Override
	public void ready() {
		refresh();
	}

	@Override
	public void disconnected() {
		deviceId = null;
		transferBusy = false;
		status.setText("연결 종료. 내려받은 기록과 서버 ACK는 휴대폰에 보존됩니다.");
	}

	@Override
	public void error(String text) {
		transferBusy = false;
		message(text);
	}

	private boolean check(byte[] response) {
		if (response.length == 0 || response[0] != 0) {
			transferBusy = false;
			message("기기 오류 코드: " + (response.length == 0 ? "응답 없음" : response[0] & 255));
			return false;
		}
		return true;
	}

	private void refresh() {
		ble.command(1, new byte[0], response -> {
			if (!check(response) || response.length != 29) {
				return;
			}
			ByteBuffer buffer = Wire.little(Arrays.copyOfRange(response, 1, response.length));
			deviceId = Long.toString(buffer.getLong(0));
			revision = buffer.getInt(10);
			status.setText("기기 " + deviceId + " · 등록 " + buffer.get(8) + " · 준비 " + buffer.get(9)
				+ "\n설정 v" + revision + " · 알고리즘 " + (buffer.getShort(14) & 65535)
				+ " · 확정 " + (buffer.getShort(16) & 65535) + " · 검증 " + (buffer.getShort(18) & 65535)
				+ "\n빈 슬롯 " + (buffer.getShort(20) & 65535) + " · 포화/수신 누락 " + buffer.getInt(22)
				+ " · 저장 오류 " + buffer.get(26) + " · 배터리 미측정");
		});
	}

	private byte[] token() {
		if (deviceId == null || !prefs.contains("token:" + deviceId)) {
			message("이 휴대폰에서 선택한 키링을 먼저 등록하세요.");
			return null;
		}
		return Wire.unhex(prefs.getString("token:" + deviceId, ""));
	}

	private void register() {
		if (deviceId == null || !ble.isReady()) {
			message("키링 연결 후 등록하세요.");
			return;
		}
		String target = deviceId;
		String owner = prefs.getString("owner", Wire.hex(Wire.randomId()));
		String secret = prefs.getString("token:" + target, Wire.hex(Wire.randomId()));
		// Persist the same retry identity before a board ACK can be lost.
		if (!prefs.edit().putString("owner", owner).putString("token:" + target, secret).commit()) {
			message("휴대폰 등록 정보 저장 실패");
			return;
		}
		ble.command(2, Wire.concat(Wire.unhex(owner), Wire.unhex(secret)), response -> {
			if (check(response)) {
				Set<String> ids = new HashSet<>(prefs.getStringSet("registered", Collections.emptySet()));
				ids.add(target);
				if (!prefs.edit().putStringSet("registered", ids).commit()) {
					message("등록 완료 표시 저장 실패. 같은 기기를 다시 등록하면 복구됩니다.");
				}
				updateRegistry();
				message("기기 " + target + " 등록 저장 ACK 수신");
				refresh();
			}
		});
	}

	private void updateRegistry() {
		Set<String> ids = prefs.getStringSet("registered", Collections.emptySet());
		registry.setText("이 휴대폰의 등록 키링 " + ids.size() + "대\n" + String.join("\n", ids));
	}

	private void sync() {
		byte[] secret = token();
		if (secret == null) {
			return;
		}
		byte[] data = new byte[1024];
		for (int i = 0; i < data.length; i++) {
			data[i] = (byte)i;
		}
		int cached = prefs.getInt("revision:" + deviceId, 0);
		int next = cached >= revision && cached > 0 ? cached : revision + 1;
		byte[] snapshot = cached == next && prefs.contains("snapshot:" + deviceId)
			? Wire.unhex(prefs.getString("snapshot:" + deviceId, "")) : Wire.randomId();
		if (!prefs.edit().putInt("revision:" + deviceId, next)
			.putString("snapshot:" + deviceId, Wire.hex(snapshot)).commit()) {
			message("동기화 재시도 정보 저장 실패");
			return;
		}
		// Explicit synthetic policy fixture; production FE supplies its server-issued values.
		byte[] settings = Wire.sync(secret, System.currentTimeMillis() / 1000, next, 1, 1, 604800, snapshot, data);
		ble.command(3, settings, response -> {
			if (check(response)) {
				if (response.length != 21 || Wire.little(response).getInt(1) != next
					|| !Arrays.equals(snapshot, Arrays.copyOfRange(response, 5, 21))) {
					message("저장 ACK의 버전/스냅샷 불일치");
					return;
				}
				message("바이너리 정보·시간 저장 및 적용 완료");
				refresh();
			}
		});
	}

	private void download() {
		byte[] secret = token();
		if (secret == null || !ble.isReady()) {
			return;
		}
		transferBusy = true;
		readQueue(deviceId, secret, 2, 0);
	}

	private void readQueue(String target, byte[] secret, int queue, int after) {
		ByteBuffer request = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN);
		request.put(secret).put((byte)queue).putInt(after);
		ble.command(4, request.array(), response -> {
			if (response.length > 0 && response[0] == Wire.NOT_FOUND) {
				if (queue == 2) {
					readQueue(target, secret, 3, 0);
				} else {
					transferBusy = false;
					message("두 큐 내려받기 완료. 서버 저장 확인 전까지 키링 원본을 유지합니다.");
					refresh();
				}
				return;
			}
			if (!check(response)) {
				return;
			}
			byte[] record = Arrays.copyOfRange(response, 1, response.length);
			try {
				outbox.save(target, record);
				readQueue(target, secret, queue, Wire.little(record).getInt(4));
			} catch (RuntimeException error) {
				transferBusy = false;
				message("휴대폰 기록 저장 실패: " + error.getMessage());
			}
		});
	}

	private void upload() {
		String address = endpoint.getText().toString().trim();
		try {
			URI uri = URI.create(address);
			if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
				throw new IllegalArgumentException("HTTP URL required");
			}
		} catch (IllegalArgumentException invalid) {
			message("테스트 서버 URL을 확인하세요.");
			return;
		}
		prefs.edit().putString("endpoint", address).apply();
		transferBusy = true;
		network.execute(() -> {
			try {
				for (Outbox.Row row : outbox.pending(null)) {
					if (row.serverStored()) {
						continue;
					}
					HttpURLConnection connection = (HttpURLConnection)URI.create(address).toURL().openConnection();
					try {
						connection.setRequestMethod("POST");
						connection.setInstanceFollowRedirects(false);
						connection.setConnectTimeout(10000);
						connection.setReadTimeout(10000);
						connection.setDoOutput(true);
						connection.setRequestProperty("Content-Type", "application/json");
						byte[] body = row.upload().toString().getBytes(StandardCharsets.UTF_8);
						connection.setFixedLengthStreamingMode(body.length);
						try (var output = connection.getOutputStream()) {
							output.write(body);
						}
						if (connection.getResponseCode() != 200) {
							throw new IllegalStateException("HTTP " + connection.getResponseCode());
						}
						try (InputStream input = connection.getInputStream()) {
							ByteArrayOutputStream received = new ByteArrayOutputStream();
							byte[] chunk = new byte[512];
							int count;
							while ((count = input.read(chunk)) != -1) {
								if (received.size() + count > 4096) {
									throw new IllegalArgumentException("Receipt too large");
								}
								received.write(chunk, 0, count);
							}
							byte[] response = received.toByteArray();
							outbox.serverReceipt(row, new JSONObject(new String(response, StandardCharsets.UTF_8)));
						}
					} finally {
						connection.disconnect();
					}
				}
				runOnUiThread(() -> {
					if (isDestroyed()) {
						return;
					}
					message("서버 저장 확인 완료. 연결된 기기에 저장 ACK를 반영합니다.");
					if (deviceId == null || !ble.isReady() || token() == null) {
						transferBusy = false;
						message("다음 기기 연결 후 업로드 버튼을 누르면 저장된 ACK를 이어서 반영합니다.");
					} else {
						ackNext(deviceId, token());
					}
				});
			} catch (Exception failure) {
				runOnUiThread(() -> {
					if (isDestroyed()) {
						return;
					}
					transferBusy = false;
					message("서버 동기화 중단·기록 보존: " + failure.getMessage());
				});
			}
		});
	}

	private void ackNext(String target, byte[] secret) {
		List<Outbox.Row> rows = outbox.pending(target);
		Outbox.Row row = rows.stream().filter(Outbox.Row::serverStored).findFirst().orElse(null);
		if (row == null) {
			transferBusy = false;
			message("키링 저장 ACK 반영 완료");
			refresh();
			return;
		}
		ble.command(5, row.boardReceipt(secret), response -> {
			if (check(response)) {
				outbox.boardAck(row);
				ackNext(target, secret);
			}
		});
	}

	@Override
	protected void onDestroy() {
		ble.close();
		network.execute(outbox::close);
		network.shutdown();
		super.onDestroy();
	}
}
