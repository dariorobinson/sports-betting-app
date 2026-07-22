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
 * Thin, typed wrapper around the subset of the Kalshi Trade API (v2) this app needs:
 * sports catalog browsing (series/events/markets), order books, and order/portfolio
 * management. Handles RSA-PSS request signing for authenticated endpoints.
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

    // ---- Portfolio (authenticated) ----

    public GetBalanceResponse getBalance() {
        return get("/portfolio/balance", null, true, GetBalanceResponse.class);
    }

    public GetPositionsResponse getPositions() {
        return get("/portfolio/positions", null, true, GetPositionsResponse.class);
    }

    public GetOrdersResponse getOrders(String status, String ticker) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (status != null && !status.isBlank()) {
            params.add("status", status);
        }
        if (ticker != null && !ticker.isBlank()) {
            params.add("ticker", ticker);
        }
        return get("/portfolio/orders", params, true, GetOrdersResponse.class);
    }

    public CreateOrderV2Response createOrder(CreateOrderV2Request request) {
        return post("/portfolio/events/orders", request, CreateOrderV2Response.class);
    }

    public CancelOrderV2Response cancelOrder(String orderId, String marketTicker) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (marketTicker != null && !marketTicker.isBlank()) {
            params.add("market_ticker", marketTicker);
        }
        return delete("/portfolio/events/orders/" + orderId, params, CancelOrderV2Response.class);
    }

    // ---- Combos / multivariate event collections (list+get are public; create is authenticated) ----

    public GetMultivariateEventCollectionsResponse listMultivariateCollections(String seriesTicker, String status) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (seriesTicker != null && !seriesTicker.isBlank()) {
            params.add("series_ticker", seriesTicker);
        }
        if (status != null && !status.isBlank()) {
            params.add("status", status);
        }
        return get("/multivariate_event_collections", params, false, GetMultivariateEventCollectionsResponse.class);
    }

    public GetMultivariateEventCollectionResponse getMultivariateCollection(String collectionTicker) {
        return get("/multivariate_event_collections/" + collectionTicker, null, false,
                GetMultivariateEventCollectionResponse.class);
    }

    /**
     * Materializes (or returns, if it already exists) an actual tradeable market for one specific
     * combination of legs — this is the only way to see Kalshi's real combo price, since combos
     * aren't pre-listed. Doesn't place an order or risk money; it just creates/looks up a listing.
     */
    public CreateMarketInMultivariateEventCollectionResponse createComboMarket(
            String collectionTicker, CreateMarketInMultivariateEventCollectionRequest request) {
        return post("/multivariate_event_collections/" + collectionTicker, request,
                CreateMarketInMultivariateEventCollectionResponse.class);
    }

    // ---- RFQs / quotes (authenticated) — combo markets have no resting order book; a real price
    // requires asking a market maker for a quote via this request-for-quote flow. ----

    public CreateRFQResponse createRfq(CreateRFQRequest request) {
        return post("/communications/rfqs", request, CreateRFQResponse.class);
    }

    public GetQuotesResponse getQuotesForRfq(String rfqId) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("rfq_id", rfqId);
        return get("/communications/quotes", params, true, GetQuotesResponse.class);
    }

    public void deleteRfq(String rfqId) {
        delete("/communications/rfqs/" + rfqId, null, Void.class);
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

    private <T> T post(String path, Object body, Class<T> responseType) {
        KalshiRequestSigner.SignedHeaders signed = signer.sign("POST", basePath + path);
        return restClient.post()
                .uri(path)
                .headers(headers -> applyAuth(headers, signed))
                .body(body)
                .retrieve()
                .onStatus(status -> status.value() >= 400, this::raiseApiException)
                .body(responseType);
    }

    private <T> T delete(String path, MultiValueMap<String, String> queryParams, Class<T> responseType) {
        KalshiRequestSigner.SignedHeaders signed = signer.sign("DELETE", basePath + path);
        return restClient.delete()
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
