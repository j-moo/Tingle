package com.ssafy.e106.features.chat.ui

/** 샘플링 없는 iOS 디코더는 최대 4메가픽셀(RGBA 약 16MiB)만 허용하고 큰 사진은 재시도 표시로 돌린다. */
internal fun chatImageAllocationAllowed(width: Int, height: Int): Boolean =
    width in 1..16000 && height in 1..16000 && width.toLong() * height <= 4L * 1024 * 1024
