package com.e106.tingle.proposal;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

public class ExpiredProposalException extends BusinessException {
	public ExpiredProposalException() {
		super(ErrorCode.PROPOSAL_EXPIRED);
	}
}
