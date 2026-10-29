package com.e106.tingle.icebreaker;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.icebreaker.dto.IcebreakerTask;
import com.e106.tingle.icebreaker.dto.IcebreakersStatus;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class IcebreakerService {
	private final IcebreakerStore store;

	public IcebreakerTask request(long roomId, long userId, UUID key) {
		if (roomId < 1 || key == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return store.request(roomId, userId, key);
	}

	public IcebreakersStatus get(long roomId, long userId) {
		if (roomId < 1) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return store.get(roomId, userId);
	}
}
