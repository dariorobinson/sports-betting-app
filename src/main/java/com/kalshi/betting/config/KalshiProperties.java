package com.kalshi.betting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Binds the {@code kalshi.*} properties from application.yml / environment variables.
 * <p>
 * Credentials are never hardcoded: {@code api-key-id} and the RSA private key are
 * expected to come from environment variables (see application.yml defaults).
 * <p>
 * {@code apiKeyId} and the private key fields are intentionally NOT {@code @NotBlank}: Kalshi's
 * market-browsing endpoints are public and don't need credentials, so the app must be able to
 * start without them configured. {@link com.kalshi.betting.auth.KalshiRequestSigner} enforces
 * their presence lazily, only when an authenticated call is actually attempted.
 */
@ConfigurationProperties(prefix = "kalshi")
@Validated
public record KalshiProperties(

        @NotBlank
        String baseUrl,

        String apiKeyId,

        /**
         * PEM-encoded RSA private key contents (PKCS#8, "-----BEGIN PRIVATE KEY-----").
         * Mutually exclusive with {@link #privateKeyPath()}; if both are set, this wins.
         */
        String privateKeyPem,

        /**
         * Filesystem path to a PEM-encoded RSA private key. Used when {@link #privateKeyPem()}
         * is not supplied directly.
         */
        String privateKeyPath
) {
}
