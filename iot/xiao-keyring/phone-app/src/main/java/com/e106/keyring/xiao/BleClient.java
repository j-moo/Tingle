package com.e106.keyring.xiao;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Consumer;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

/** All application operations and callbacks are serialized on the main looper. */
@SuppressLint("MissingPermission")
public final class BleClient {
	public interface Listener {
		void found(BluetoothDevice device);

		void ready();

		void disconnected();

		void error(String message);
	}

	private final Context context;
	private final Listener listener;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final BluetoothManager manager;
	private BluetoothGatt gatt;
	private BluetoothGattCharacteristic rx;
	private BluetoothGattCharacteristic tx;
	private boolean ready;
	private boolean scanning;
	private boolean discovering;
	private int requestId;
	private int activeRequest;
	private int activeOp;
	private byte[] outgoing;
	private int written;
	private Consumer<byte[]> pending;
	private final ByteArrayOutputStream incoming = new ByteArrayOutputStream();
	private final Runnable timeout = () -> fail("BLE 응답 시간 초과. 재연결 후 같은 작업을 재시도하세요.");

	private final ScanCallback scanCallback = new ScanCallback() {
		@Override
		public void onScanResult(int type, ScanResult result) {
			handler.post(() -> listener.found(result.getDevice()));
		}

		@Override
		public void onScanFailed(int errorCode) {
			handler.post(() -> {
				scanning = false;
				listener.error("검색 실패: " + errorCode);
			});
		}
	};

	private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context receivedContext, Intent intent) {
			BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
			if (gatt == null || device == null || !device.equals(gatt.getDevice())) {
				return;
			}
			int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
			if (state == BluetoothDevice.BOND_BONDED) {
				discover();
			} else if (state == BluetoothDevice.BOND_NONE) {
				fail("페어링이 완료되지 않았습니다.");
			}
		}
	};

	private final BluetoothGattCallback callback = new BluetoothGattCallback() {
		@Override
		public void onConnectionStateChange(BluetoothGatt current, int status, int state) {
			handler.post(() -> {
				if (current != gatt) {
					return;
				}
				if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
					closeConnection();
					listener.disconnected();
					return;
				}
				if (state == BluetoothProfile.STATE_CONNECTED) {
					if (current.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) {
						discover();
					} else if (current.getDevice().getBondState() == BluetoothDevice.BOND_NONE
						&& !current.getDevice().createBond()) {
						fail("페어링을 시작하지 못했습니다.");
					}
				}
			});
		}

		@Override
		public void onServicesDiscovered(BluetoothGatt current, int status) {
			handler.post(() -> {
				if (current != gatt) {
					return;
				}
				BluetoothGattService service = current.getService(Wire.SERVICE);
				if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
					fail("테스트 펌웨어 서비스를 찾지 못했습니다.");
					return;
				}
				rx = service.getCharacteristic(Wire.RX);
				tx = service.getCharacteristic(Wire.TX);
				if (rx == null || tx == null || !current.setCharacteristicNotification(tx, true)) {
					fail("Notify 구독을 시작하지 못했습니다.");
					return;
				}
				BluetoothGattDescriptor ccc = tx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
				if (ccc == null) {
					fail("CCC가 없습니다.");
					return;
				}
				boolean started;
				if (Build.VERSION.SDK_INT >= 33) {
					started = current.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
						== BluetoothStatusCodes.SUCCESS;
				} else {
					ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
					started = current.writeDescriptor(ccc);
				}
				if (!started) {
					fail("CCC 쓰기를 시작하지 못했습니다.");
				}
			});
		}

		@Override
		public void onDescriptorWrite(BluetoothGatt current, BluetoothGattDescriptor descriptor, int status) {
			handler.post(() -> {
				if (current != gatt) {
					return;
				}
				if (status != BluetoothGatt.GATT_SUCCESS) {
					fail("암호화된 Notify 구독 실패: " + status);
					return;
				}
				handler.removeCallbacks(timeout);
				ready = true;
				listener.ready();
			});
		}

		@Override
		public void onCharacteristicWrite(BluetoothGatt current,
			BluetoothGattCharacteristic characteristic, int status) {
			handler.post(() -> {
				if (current != gatt || pending == null) {
					return;
				}
				if (status != BluetoothGatt.GATT_SUCCESS) {
					fail("BLE 쓰기 실패: " + status);
					return;
				}
				writeNext();
			});
		}

		@Override
		public void onCharacteristicChanged(BluetoothGatt current,
			BluetoothGattCharacteristic characteristic, byte[] value) {
			byte[] copy = value.clone();
			handler.post(() -> receive(current, copy));
		}

		@Override
		@SuppressWarnings("deprecation")
		public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic characteristic) {
			if (Build.VERSION.SDK_INT < 33) {
				byte[] copy = characteristic.getValue().clone();
				handler.post(() -> receive(current, copy));
			}
		}
	};

	public BleClient(Context context, Listener listener) {
		this.context = context;
		this.listener = listener;
		manager = context.getSystemService(BluetoothManager.class);
		IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
		if (Build.VERSION.SDK_INT >= 33) {
			context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED);
		} else {
			context.registerReceiver(bondReceiver, filter);
		}
	}

	public void scan() {
		if (manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled()) {
			listener.error("블루투스를 켜세요.");
			return;
		}
		stopScan();
		ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(Wire.SERVICE)).build();
		manager.getAdapter().getBluetoothLeScanner().startScan(Collections.singletonList(filter),
			new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
		scanning = true;
	}

	public void stopScan() {
		if (scanning && manager.getAdapter().isEnabled()) {
			manager.getAdapter().getBluetoothLeScanner().stopScan(scanCallback);
		}
		scanning = false;
	}

	public void connect(BluetoothDevice device) {
		stopScan();
		closeConnection();
		handler.postDelayed(timeout, 30000); // Connection/pairing deadline, never a queue delay.
		gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
	}

	private void discover() {
		if (!discovering && gatt != null) {
			discovering = true;
			if (!gatt.discoverServices()) {
				fail("서비스 검색을 시작하지 못했습니다.");
			}
		}
	}

	public boolean isReady() {
		return ready && pending == null;
	}

	public void command(int op, byte[] body, Consumer<byte[]> result) {
		if (!isReady()) {
			listener.error("연결 준비 또는 이전 요청 완료를 기다려 주세요.");
			return;
		}
		activeRequest = ++requestId;
		activeOp = op;
		pending = result;
		outgoing = Wire.frame(op, activeRequest, body);
		written = 0;
		incoming.reset();
		handler.postDelayed(timeout, 15000);
		writeNext();
	}

	private void writeNext() {
		if (written >= outgoing.length) {
			return;
		}
		byte[] part = Arrays.copyOfRange(outgoing, written, Math.min(written + 20, outgoing.length));
		written += part.length;
		boolean started;
		if (Build.VERSION.SDK_INT >= 33) {
			started = gatt.writeCharacteristic(rx, part, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
				== BluetoothStatusCodes.SUCCESS;
		} else {
			rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
			rx.setValue(part);
			started = gatt.writeCharacteristic(rx);
		}
		if (!started) {
			fail("BLE 쓰기 요청을 시작하지 못했습니다.");
		}
	}

	private void receive(BluetoothGatt current, byte[] part) {
		if (current != gatt || pending == null) {
			return;
		}
		if (incoming.size() + part.length > Wire.MAX_FRAME) {
			fail("응답 최대 길이 초과");
			return;
		}
		incoming.write(part, 0, part.length);
		byte[] frame = incoming.toByteArray();
		if (frame.length < 10) {
			return;
		}
		int expected = (Wire.little(frame).getShort(8) & 65535) + 14;
		if (expected > Wire.MAX_FRAME || frame.length > expected) {
			fail("응답 프레임 길이 오류");
			return;
		}
		if (frame.length == expected) {
			try {
				byte[] body = Wire.response(frame, activeOp, activeRequest);
				Consumer<byte[]> complete = pending;
				pending = null;
				handler.removeCallbacks(timeout);
				complete.accept(body);
			} catch (IllegalArgumentException error) {
				fail(error.getMessage());
			}
		}
	}

	private void fail(String message) {
		closeConnection();
		listener.error(message);
		listener.disconnected();
	}

	public void closeConnection() {
		handler.removeCallbacks(timeout);
		pending = null;
		ready = false;
		discovering = false;
		incoming.reset();
		if (gatt != null) {
			gatt.disconnect();
			gatt.close();
			gatt = null;
		}
	}

	public void close() {
		stopScan();
		closeConnection();
		context.unregisterReceiver(bondReceiver);
	}
}
