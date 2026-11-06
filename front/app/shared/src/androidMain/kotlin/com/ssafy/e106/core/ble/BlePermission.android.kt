package com.ssafy.e106.core.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Android 12부터 위치 권한 대신 근처 기기를 요청한다. Android 6~11은 위치 권한/서비스가 탐색에 필요하다. */
internal fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

internal fun Context.bleAvailability(): BleAvailability {
    val adapter = getSystemService(BluetoothManager::class.java)?.adapter
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) || adapter == null) return BleAvailability.Unsupported
    if (blePermissions().any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED })
        return BleAvailability.PermissionRequired
    return try {
        if (!adapter.isEnabled) BleAvailability.BluetoothOff
        else if (Build.VERSION.SDK_INT < 31 && !LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java)))
            BleAvailability.LocationOff
        else BleAvailability.Ready
    } catch (_: SecurityException) { BleAvailability.PermissionRequired }
}

@Composable actual fun rememberBlePermission(): BlePermission {
    val context = LocalContext.current
    var state by remember { mutableStateOf(context.bleAvailability()) }
    var denied by rememberSaveable { mutableStateOf(false) }   // 회전 뒤에도 "다시 묻지 않음"이면 바로 설정으로
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        state = context.bleAvailability()
        denied = state == BleAvailability.PermissionRequired
    }
    LifecycleResumeEffect(Unit) {
        state = context.bleAvailability()
        if (state != BleAvailability.PermissionRequired) denied = false   // 설정에서 허용했다가 다시 회수하면 다시 물을 수 있다
        onPauseOrDispose { }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { state = context.bleAvailability() }
        }
        // Bluetooth 방송은 시스템 Bluetooth 프로세스가 보내므로 해당 시스템 액션만 받는다.
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply { addAction(LocationManager.MODE_CHANGED_ACTION) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    // 일부 기기는 블루투스 · 위치 설정 화면이 없다(ActivityNotFoundException). 그때는 휴대폰 설정 첫 화면으로 보낸다
    fun open(intent: Intent) {
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    return BlePermission(state, request = { launcher.launch(blePermissions()) },
        openAppSettings = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
        openBluetoothSettings = { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
        openLocationSettings = { open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        denied = denied && state == BleAvailability.PermissionRequired)
}
