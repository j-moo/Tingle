package com.e106.tingle.icebreaker.dto;

import java.util.List;

public record IcebreakersStatus(String status, List<String> questions, String source, String promptVersion) {
}
