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
    /** 서버 등록 목록과 앱 내부 별명을 분리한다. 기본 이름은 목록 순서로 표시한다. */
    val keyrings: List<com.ssafy.e106.features.keyring.data.OwnedKeyring> = emptyList(),
    val keyringAliases: Map<Long, String> = emptyMap(),
)

enum class HomeAction { Refresh, Proposals, Chats, Profile, Keyring }
