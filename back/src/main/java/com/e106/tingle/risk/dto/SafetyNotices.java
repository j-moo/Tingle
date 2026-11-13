package com.e106.tingle.risk.dto;

import java.util.List;

/** 아직 닫지(확인하지) 않은 내 위험 안내 목록(오래된 순). 앱에 보여 준 뒤에도 닫기 전까지는 계속 나온다. */
public record SafetyNotices(List<SafetyNoticeView> items) {
}
