package com.e106.tingle.keyring;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.keyring.dto.Keyrings;
import com.e106.tingle.keyring.dto.PurchaseInfo;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class KeyringReadService {

	private final KeyringReadRepository keyrings;
	private final KeyringProperties properties;

	@Transactional(readOnly = true)
	public Keyrings getKeyrings(long userId) {
		return new Keyrings(keyrings.findOwned(userId));
	}

	@Transactional(readOnly = true)
	public PurchaseInfo getPurchaseInfo(long userId) {
		return new PurchaseInfo(properties.purchaseUrl(), !keyrings.hasActiveBinding(userId));
	}
}
