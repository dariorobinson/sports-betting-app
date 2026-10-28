package com.kalshi.betting.client;

import com.kalshi.betting.auth.KalshiRequestSigner;
import com.kalshi.betting.client.dto.*;
import com.kalshi.betting.config.KalshiProperties;
import com.kalshi.betting.exception.KalshiApiException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * Thin, typed wrapper around the subset of the Kalshi Trade API (v2) this app needs: sports catalog
 * browsing (series/events/markets), order books, and settlement history (for realized P&L). This app
 * never places or cancels an order — it's an advisory-only research tool — so only read-only
 * endpoints are wrapped. Handles RSA-PSS request signing for the authenticated ones (order books and
 * settlements).
 */
@Component
public class KalshiApiClient {

    private final RestClient restClient;
    private final KalshiRequestSigner signer;
    private final String basePath;

    public KalshiApiClient(RestClient kalshiRestClient, KalshiRequestSigner signer, KalshiProperties properties) {
        this.restClient = kalshiRestClient;
        this.signer = signer;
        this.basePath = URI.create(properties.baseUrl()).getPath();
    }

    // ---- Catalog (public endpoints) ----

    public GetSeriesListResponse listSeries(String category) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (category != null && !category.isBlank()) {
            params.add("category", category);
        }
        return get("/series", params, false, GetSeriesListResponse.class);
    }

    public GetEventsResponse listEvents(String seriesTicker, String status, boolean withNestedMarkets) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (seriesTicker != null && !seriesTicker.isBlank()) {
            params.add("series_ticker", seriesTicker);
        }
        if (status != null && !status.isBlank()) {
            params.add("status", status);
        }
        params.add("with_nested_markets", String.valueOf(withNestedMarkets));
        return get("/events", params, false, GetEventsResponse.class);
    }

    public GetEventResponse getEvent(String eventTicker, boolean withNestedMarkets) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("with_nested_markets", String.valueOf(withNestedMarkets));
        return get("/events/" + eventTicker, params, false, GetEventResponse.class);
    }

    /** Batch event lookup — resolves many event tickers in one call instead of one-at-a-time. */
    public GetEventsResponse listEventsByTickers(java.util.List<String> eventTickers, boolean withNestedMarkets) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("tickers", String.join(",", eventTickers));
        params.add("with_nested_markets", String.valueOf(withNestedMarkets));
        return get("/events", params, false, GetEventsResponse.class);
    }

    public GetMarketResponse getMarket(String ticker) {
        return get("/markets/" + ticker, null, false, GetMarketResponse.class);
    }

    // ---- Market data (authenticated per Kalshi's spec) ----

    public GetMarketOrderbookResponse getOrderbook(String ticker, int depth) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (depth > 0) {
            params.add("depth", String.valueOf(depth));
        }
        return get("/markets/" + ticker + "/orderbook", params, true, GetMarketOrderbookResponse.class);
    }

    // ---- Settlements (authenticated) — realized P&L tracking only, no placement ----

    /**
     * One page of settled markets (resolved bets), newest first. {@code minTs} (epoch seconds) bounds
     * how far back to look; {@code cursor} (from a prior page's response) fetches the next page — pass
     * null for the first page. Authenticated.
     */
    public GetSettlementsResponse getSettlements(Long minTs, String cursor) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("limit", "100");
        if (minTs != null) {
            params.add("min_ts", String.valueOf(minTs));
        }
        if (cursor != null && !cursor.isBlank()) {
            params.add("cursor", cursor);
        }
        return get("/portfolio/settlements", params, true, GetSettlementsResponse.class);
    }

    // ---- HTTP plumbing ----

    private <T> T get(String path, MultiValueMap<String, String> queryParams, boolean authenticated,
                       Class<T> responseType) {
        KalshiRequestSigner.SignedHeaders signed = authenticated ? signer.sign("GET", basePath + path) : null;
        return restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path(path);
                    if (queryParams != null && !queryParams.isEmpty()) {
                        uriBuilder.queryParams(queryParams);
                    }
                    return uriBuilder.build();
                })
                .headers(headers -> applyAuth(headers, signed))
                .retrieve()
                .onStatus(status -> status.value() >= 400, this::raiseApiException)
                .body(responseType);
    }

    private void applyAuth(org.springframework.http.HttpHeaders headers, KalshiRequestSigner.SignedHeaders signed) {
        if (signed == null) {
            return;
        }
        headers.add("KALSHI-ACCESS-KEY", signed.accessKey());
        headers.add("KALSHI-ACCESS-SIGNATURE", signed.signature());
        headers.add("KALSHI-ACCESS-TIMESTAMP", signed.timestamp());
    }

    private void raiseApiException(org.springframework.http.HttpRequest request,
                                    org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
        String body = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        throw new KalshiApiException(response.getStatusCode(), body);
    }
}
