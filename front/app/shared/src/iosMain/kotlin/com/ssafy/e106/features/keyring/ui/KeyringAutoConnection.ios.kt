package com.ssafy.e106.features.keyring.ui

import androidx.compose.runtime.Composable
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.features.keyring.data.OwnedKeyring
import org.koin.compose.viewmodel.koinViewModel

@Composable internal actual fun keyringBleModel(): BleProbeViewModel = koinViewModel()
@Composable internal actual fun keyringEncounterModel(): KeyringEncounterViewModel = koinViewModel()
@Composable internal actual fun KeyringAutoConnectionControl(
    session: Session.SignedIn, selected: OwnedKeyring?, link: BleProbeState,
) = Unit
@Composable internal actual fun KeyringAutoConnectionHost(suspended: Boolean) = Unit
internal actual fun retainKeyringAutoConnection(link: BleProbeState) = false
internal actual fun managesKeyringAutoConnection(serial: String) = false
