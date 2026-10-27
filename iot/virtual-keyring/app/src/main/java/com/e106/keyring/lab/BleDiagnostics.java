package com.e106.keyring.lab;

import java.util.ArrayList;
import java.util.List;

import com.e106.keyring.core.ExtendedAdvertisement;

import android.Manifest;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

public final class BleDiagnostics {
	private BleDiagnostics() {
	}

	public static String[] permissions() {
		if (Build.VERSION.SDK_INT >= 31) {
			return new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
				Manifest.permission.BLUETOOTH_ADVERTISE};
		}
		return new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
	}

	public static boolean hasPermissions(Context context) {
		for (String permission : permissions()) {
			if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
				return false;
			}
		}
		return true;
	}

	public static BluetoothAdapter adapter(Context context) {
		BluetoothManager manager = context.getSystemService(BluetoothManager.class);
		return manager == null ? null : manager.getAdapter();
	}

	public static boolean locationEnabled(Context context) {
		if (Build.VERSION.SDK_INT >= 31) {
			return true;
		}
		LocationManager manager = context.getSystemService(LocationManager.class);
		if (Build.VERSION.SDK_INT >= 28) {
			return manager != null && manager.isLocationEnabled();
		}
		return Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE, 0) != 0;
	}

	@SuppressWarnings("deprecation")
	public static List<String> inspect(Context context) {
		List<String> report = new ArrayList<>();
		report.add(Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE);
		report.add("자동 날짜 및 시간: " + (Settings.Global.getInt(context.getContentResolver(),
			Settings.Global.AUTO_TIME, 0) == 1 ? "켜짐" : "꺼짐 · 양쪽에서 자동 설정을 켜주세요"));
		Vibrator vibrator = Build.VERSION.SDK_INT >= 31
			? context.getSystemService(VibratorManager.class).getDefaultVibrator()
			: context.getSystemService(Vibrator.class);
		report.add("진동 모터: " + (vibrator != null && vibrator.hasVibrator()
			? "있음 · 시스템 알림 진동·방해 금지 설정도 확인해주세요" : "없음 · 화면 LED·흔들림만 표시합니다"));
		NotificationManager notifications = context.getSystemService(NotificationManager.class);
		report.add("상태 알림: " + (notifications.areNotificationsEnabled()
			? "허용됨 · 실행 중 알림에서 중지 가능" : "차단됨 · 앱으로 돌아와 통신을 중지하세요"));
		PowerManager power = context.getSystemService(PowerManager.class);
		report.add("배터리 최적화: " + (power.isIgnoringBatteryOptimizations(context.getPackageName())
			? "제외됨" : "적용 중 · 장시간 화면 꺼짐 시험은 이 앱을 제외해주세요"));
		boolean feature = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
		report.add("BLE 기능 선언: " + (feature ? "있음" : "없음"));
		if (!hasPermissions(context)) {
			report.add("권한: 허용 필요 · 아래 권한 버튼을 눌러주세요");
			return report;
		}
		report.add("BLE 권한: 허용됨");
		try {
			BluetoothAdapter adapter = adapter(context);
			if (adapter == null || !adapter.isEnabled()) {
				report.add("Bluetooth: " + (adapter == null ? "어댑터 없음" : "꺼짐"));
				return report;
			}
			report.add("Bluetooth: 켜짐");
			report.add("스캔 API: " + (adapter.getBluetoothLeScanner() != null ? "사용 가능" : "사용 불가"));
			report.add("광고 API: " + (adapter.getBluetoothLeAdvertiser() != null ? "사용 가능" : "사용 불가"));
			report.add("다중 광고 지원 보고: " + (adapter.isMultipleAdvertisementSupported() ? "예" : "아니요"));
			boolean extended = adapter.isLeExtendedAdvertisingSupported();
			int maximum = adapter.getLeMaximumAdvertisingDataLength();
			report.add("확장 광고 지원 보고: " + (extended ? "지원" : "미지원"));
			report.add("기기 최대 광고 데이터: " + maximum + "바이트 · 앱 상한 255바이트");
			report.add("한 묶음 전송: " + (ExtendedAdvertisement.supported(extended, maximum)
				? "시작 가능 · 실제 시작 결과는 탐색·시험에서 확인"
				: "일반 광고 조각 전송 사용 · 현재 묶음에 216바이트 필요"));
			report.add("확장 광고 갱신 상한: 251바이트 · 서비스 식별자·형식 정보 포함");
			report.add("스캔 하드웨어 필터 지원 보고: " + (adapter.isOffloadedFilteringSupported() ? "예" : "아니요"));
			if (Build.VERSION.SDK_INT < 31) {
				report.add("위치 서비스: " + (locationEnabled(context) ? "켜짐" : "꺼짐 · 스캔 전 켜주세요"));
			}
			report.add("실제 무선 지원은 광고 시작·검색·왕복 통신 시험으로 확인합니다.");
		} catch (SecurityException exception) {
			report.add("권한이 변경되었습니다. 권한 허용 후 다시 진단해주세요.");
		} catch (RuntimeException exception) {
			report.add("광고 지원 조회 실패 · Bluetooth를 켜고 다시 진단해주세요.");
		}
		return report;
	}
}
