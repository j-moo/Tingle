package com.ssafy.e106.features.settings.ui

enum class SettingsLoad { Loading, Ready, Failed }
enum class SettingsSave { Idle, Changed, Saving, Failed, Unknown }
enum class SettingsSync { Unknown, NoKeyring, Disconnected, Pending, Syncing, Applied, Failed, RecheckRequired }
enum class KeyringConnection { Unknown, Connected, Disconnected }

/** 저장 성공은 호출부가 확인한다. Applied는 최신 저장 버전의 기기 적용 확인을 받은 경우에만 사용한다. */
data class MatchingSettingsState(
    val load: SettingsLoad = SettingsLoad.Loading,
    val matching: Boolean? = null,
    val photoRequired: Boolean? = null,
    val ageLabel: String? = null,
    val save: SettingsSave = SettingsSave.Idle,
    val sync: SettingsSync = SettingsSync.Unknown,
    val conflict: SettingsConflictState<ConflictValues.Matching>? = null,
) {
    val editable: Boolean get() = conflict == null && load == SettingsLoad.Ready && save.allowsEditing
}

data class KeyringSettingsState(
    val load: SettingsLoad = SettingsLoad.Loading,
    val hasKeyring: Boolean? = null,
    val connection: KeyringConnection = KeyringConnection.Unknown,
    val sync: SettingsSync = SettingsSync.Unknown,
    val vibration: Boolean? = null,
    val led: Boolean? = null,
    val batteryPercent: Int? = null,
    val firmware: String? = null,
    val lastSyncLabel: String? = null,
    val pendingUploadCount: Int? = null,
    val waitingPeerCount: Int? = null,
    val save: SettingsSave = SettingsSave.Idle,
) {
    val editable: Boolean get() = load == SettingsLoad.Ready && hasKeyring == true && save.allowsEditing
    // 연결이 끊겼거나 소유 여부가 불명확하면 오래된 성공 표시를 재사용하지 않는다.
    val displaySync: SettingsSync get() = when {
        hasKeyring == false -> SettingsSync.NoKeyring
        hasKeyring != true || connection == KeyringConnection.Unknown -> SettingsSync.Unknown
        connection == KeyringConnection.Disconnected -> SettingsSync.Disconnected
        sync == SettingsSync.NoKeyring || sync == SettingsSync.Disconnected -> SettingsSync.Unknown
        else -> sync
    }
    val canSync: Boolean get() = editable && save == SettingsSave.Idle &&
        connection == KeyringConnection.Connected && displaySync != SettingsSync.Syncing
}

private val SettingsSave.allowsEditing: Boolean
    get() = this == SettingsSave.Idle || this == SettingsSave.Changed || this == SettingsSave.Failed

enum class MatchingSettingsAction { Back, Refresh, Age, Keyring, RetrySave, CheckSave }
enum class KeyringSettingsAction { Back, Refresh, Sync, Register, Reset, LostSupport, RetrySave, CheckSave }
