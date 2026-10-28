package com.e106.tingle.proposal;

import lombok.Getter;

/** matching의 회원 쌍을 투영한다. 별도 테이블·별도 식별자를 만들지 않는다. */
@Getter
public final class PairRelation {
	private final Long id;
	private final long userLow;
	private final long userHigh;

	public PairRelation(long id, long first, long second) {
		if (id <= 0 || first <= 0 || second <= 0 || first >= second) {
			throw new IllegalArgumentException("Invalid matching membership");
		}
		this.id = id;
		this.userLow = first;
		this.userHigh = second;
	}

	static PairRelation from(MatchProposal matching) {
		return new PairRelation(matching.getId(), matching.getUserLow(), matching.getUserHigh());
	}

	public boolean includes(long userId) {
		return userLow == userId || userHigh == userId;
	}

	public long partnerOf(long userId) {
		if (!includes(userId)) {
			throw new IllegalArgumentException("Not a matching participant");
		}
		return userLow == userId ? userHigh : userLow;
	}
}
