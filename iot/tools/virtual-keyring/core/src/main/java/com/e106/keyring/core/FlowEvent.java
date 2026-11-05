package com.e106.keyring.core;

import java.util.UUID;

/** Language-independent process record. No transport secrets or real profile text. */
public final class FlowEvent {
	public final String code;
	public final UUID peer;
	public final UUID eventId;
	public final String detail;

	public FlowEvent(String code, UUID peer, UUID eventId, String detail) {
		this.code = code;
		this.peer = peer;
		this.eventId = eventId;
		this.detail = detail;
	}
}
