package com.e106.tingle.withdrawal;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.e106.tingle.global.i18n.Messages;
import com.e106.tingle.withdrawal.dto.RejoinRestrictionResponse;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class RejoinRestrictedAdvice {
	@ExceptionHandler(RejoinRestrictedException.class)
	public ResponseEntity<RejoinRestrictionResponse> restricted(RejoinRestrictedException exception) {
		return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(new RejoinRestrictionResponse(
			"REJOIN_RESTRICTED", Messages.get("error.REJOIN_RESTRICTED"), exception.getRejoinAvailableAt()));
	}
}
