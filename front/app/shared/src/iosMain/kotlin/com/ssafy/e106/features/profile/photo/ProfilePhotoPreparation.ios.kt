package com.ssafy.e106.features.profile.photo

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable actual fun ProfilePhotoPreparation(onReady: (ByteArray) -> Unit, onClose: () -> Unit, canApply: Boolean) {
    TextButton(onClick = onClose) { Text(stringResource(Res.string.optional_platform_unavailable)) }
}
@Composable actual fun ProfilePhotoBytes(bytes: ByteArray) { Text(stringResource(Res.string.onboarding_existing_photo)) }
