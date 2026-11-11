package com.ssafy.e106.features.proposals.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/**
 * 동그란 상대 사진(목록 · 프로필). 사진이 준비되기 전(받는 중 · 없음 · 실패)엔 회색 원에 닉네임 첫 글자, 준비되면 사진.
 * 프로필에서 실패했으면 눌러서 다시 받는다(onRetry).
 */
@Composable
fun ProposalAvatar(status: ProposalPhotoState, expected: ProposalPhotoRequest?, image: ProposalPhotoImage?,
    name: String?, size: Dp, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val visible = resolveProposalPhoto(status, expected, image?.request, image != null)
    val retry = onRetry?.takeIf { visible == ProposalPhotoState.Failed }
    val retryLabel = stringResource(Res.string.proposal_profile_retry_photo)
    Box(modifier.size(size).clip(CircleShape).background(AppColors.SurfaceMuted)
        .then(if (retry != null) Modifier.semantics { contentDescription = retryLabel }
            .clickable(role = Role.Button, onClickLabel = retryLabel, onClick = retry) else Modifier),
        contentAlignment = Alignment.Center) {
        when {
            visible == ProposalPhotoState.Ready && image != null -> Image(image.bitmap, stringResource(Res.string.proposal_photo_description),
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // 다시 받을 수 있으면 ↻, 아니면 첫 글자(받는 중에도 같은 모양, 장식이라 읽지 않음)
            else -> Text(if (retry != null) "↻" else name?.trim()?.firstOrNull()?.toString().orEmpty(),
                style = if (size >= 64.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                color = AppColors.TextSecondary, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}
