package com.ssafy.e106.features.home.ui

enum class HomeLoadState { Loading, Ready, Failed }
enum class HomeKeyringState {
    Checking, Registered, Ready, Disconnected, DisconnectedPending, Syncing, SyncFailed, Missing, RecheckRequired, StorageFull, Unknown,
}

/** 표시용 스냅샷. null은 미확인이며 0/OFF/미등록으로 바꾸지 않는다. */
data class HomeState(
    val load: HomeLoadState = HomeLoadState.Loading,
    val matchingEnabled: Boolean? = null,
    val savingMatching: Boolean = false,
    val matchingSaveFailed: Boolean = false,
    val keyring: HomeKeyringState = HomeKeyringState.Unknown,
    /** 서버에 등록된 내 키링(펌웨어). 이름 · 시리얼은 서버가 주지 않아 "내 키링 n"으로만 보인다 */
    val keyrings: List<com.ssafy.e106.features.keyring.data.OwnedKeyring> = emptyList(),
)

enum class HomeAction { Refresh, Proposals, Chats, Profile, Keyring }
