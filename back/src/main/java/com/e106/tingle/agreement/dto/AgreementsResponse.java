package com.e106.tingle.agreement.dto;

import java.util.List;

import com.e106.tingle.agreement.AgreementProperties;

public record AgreementsResponse(List<AgreementProperties.Item> agreements) {
}
