package com.ssafy.e106.features.home.ui

enum class HomeLoadState { Loading, Ready, Failed }
enum class HomeKeyringState {
    Ready, Disconnected, DisconnectedPending, Syncing, SyncFailed, Missing, RecheckRequired, StorageFull, Unknown,
}

/** 표시용 스냅샷. null은 미확인이며 0/OFF/미등록으로 바꾸지 않는다. */
data class HomeState(
    val load: HomeLoadState = HomeLoadState.Loading,
    val matchingEnabled: Boolean? = null,
    val savingMatching: Boolean = false,
    val matchingSaveFailed: Boolean = false,
    val keyring: HomeKeyringState = HomeKeyringState.Unknown,
    val proposalCount: Int? = null,
    val chatCount: Int? = null,
    val batteryPercent: Int? = null,
    val firmware: String? = null,
    val lastSyncLabel: String? = null,
    val pendingUploadCount: Int? = null,
    val waitingPeerCount: Int? = null,
)

enum class HomeAction { Refresh, Proposals, Chats, Profile, Keyring, Preferences }
