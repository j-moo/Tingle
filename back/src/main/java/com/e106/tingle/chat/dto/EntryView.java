package com.e106.tingle.chat.dto;

import java.util.List;

public record EntryView(ChatRoomView chatRoom, List<NoticeView> notices) {
}
