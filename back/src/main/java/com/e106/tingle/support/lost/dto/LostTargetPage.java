package com.e106.tingle.support.lost.dto;

import java.util.List;
import java.util.UUID;

public record LostTargetPage(List<Target> items) {

	public record Target(long deviceId, UUID ownerGeneration, boolean canRequest) {
	}
}
