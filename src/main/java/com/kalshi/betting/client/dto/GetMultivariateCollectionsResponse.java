package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Response from {@code GET /multivariate_event_collections} — used only to check combo eligibility
 *  (see {@code KalshiApiClient#listMultivariateCollectionsForEvent}), so only the list itself matters;
 *  the individual collection objects are never read, just whether the list is empty or not. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GetMultivariateCollectionsResponse(List<MultivariateContractSummary> multivariateContracts,
                                                   String cursor) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MultivariateContractSummary(String collectionTicker) {
    }
}
