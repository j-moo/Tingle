package com.ssafy.e106.features.profile.photo

import androidx.compose.runtime.Composable

/** Local preparation only. The caller uploads after checking the saved consent again. */
@Composable expect fun ProfilePhotoPreparation(onReady: (ByteArray) -> Unit, onClose: () -> Unit, canApply: Boolean)
@Composable expect fun ProfilePhotoBytes(bytes: ByteArray)
