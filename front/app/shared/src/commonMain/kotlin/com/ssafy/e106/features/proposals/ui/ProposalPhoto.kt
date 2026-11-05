package com.ssafy.e106.features.proposals.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** generation은 화면 재조회마다 호출부가 새로 부여하는 값이며 서버의 profileVersion이 아니다. */
data class ProposalPhotoRequest(val viewerId: Long, val proposalId: Long, val generation: Long) {
    val valid: Boolean get() = viewerId > 0 && proposalId > 0 && generation > 0
}

/** 현재 표시용 메모리만 전달한다. URL·파일 경로·디스크/전역 캐시는 이 화면에 없다. */
data class ProposalPhotoImage(val request: ProposalPhotoRequest, val bitmap: ImageBitmap)

/** 재조회/삭제/권한 종료 때 예전 이미지가 남아 있어도 그리지 않는다. 요청 취소·참조 해제는 호출부 책임이다. */
fun resolveProposalPhoto(status: ProposalPhotoState, expected: ProposalPhotoRequest?,
    loaded: ProposalPhotoRequest?, hasImage: Boolean): ProposalPhotoState = when {
    status != ProposalPhotoState.Ready -> status
    !hasImage || expected?.valid != true || expected != loaded -> ProposalPhotoState.Failed
    else -> ProposalPhotoState.Ready
}

@Composable
fun ProposalPhoto(status: ProposalPhotoState, expected: ProposalPhotoRequest?, image: ProposalPhotoImage?,
    modifier: Modifier = Modifier, compact: Boolean = false, onRetry: (() -> Unit)? = null) {
    val visible = resolveProposalPhoto(status, expected, image?.request, image != null)
    Surface(modifier, shape = if (compact) AppShapes.Small else AppShapes.Card, color = AppColors.SurfaceMuted) {
        if (visible == ProposalPhotoState.Ready && image != null) {
            Image(image.bitmap, stringResource(Res.string.proposal_photo_description),
                Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
        } else {
            Column(Modifier.fillMaxWidth().padding(if (compact) AppSpacing.Small else AppSpacing.ExtraLarge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                Text(stringResource(when (visible) {
                    ProposalPhotoState.NotProvided, ProposalPhotoState.Removed -> if (compact) Res.string.proposal_list_no_photo else Res.string.proposal_profile_no_photo
                    ProposalPhotoState.Loading -> if (compact) Res.string.proposal_list_photo_loading else Res.string.proposal_profile_photo_loading
                    ProposalPhotoState.Unavailable -> Res.string.proposal_photo_unavailable
                    ProposalPhotoState.Failed, ProposalPhotoState.Ready -> if (compact) Res.string.proposal_list_photo_failed else Res.string.proposal_profile_photo_failed
                }), style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary)
                if (visible == ProposalPhotoState.Loading && !compact) CircularProgressIndicator(color = AppColors.SelectionBorder)
                if (visible == ProposalPhotoState.Failed && !compact && onRetry != null)
                    AppButton(stringResource(Res.string.proposal_profile_retry_photo), variant = AppButtonVariant.Secondary,
                        onClick = onRetry)
            }
        }
    }
}
