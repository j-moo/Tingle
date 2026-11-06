package com.e106.tingle.proposal;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PairRelationRepository {
	private final ProposalRepository proposals;

	public Optional<PairRelation> findById(long id) {
		return proposals.findById(id).filter(MatchProposal::hasMembers).map(PairRelation::from);
	}

	public List<PairRelation> findAllById(Iterable<Long> ids) {
		return proposals.findAllById(ids).stream().filter(MatchProposal::hasMembers).map(PairRelation::from).toList();
	}
}
