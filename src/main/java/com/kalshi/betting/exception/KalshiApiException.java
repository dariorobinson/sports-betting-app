package com.kalshi.betting.exception;

import org.springframework.http.HttpStatusCode;

/**
 * Wraps a failed call to the Kalshi API, preserving the upstream status code and body
 * so callers of our own API can see what Kalshi actually rejected.
 */
public class KalshiApiException extends RuntimeException {

    private final HttpStatusCode statusCode;
    private final String responseBody;

    public KalshiApiException(HttpStatusCode statusCode, String responseBody) {
        super("Kalshi API request failed with status " + statusCode.value() + ": " + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public HttpStatusCode statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
