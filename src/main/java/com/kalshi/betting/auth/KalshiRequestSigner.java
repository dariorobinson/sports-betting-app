package com.kalshi.betting.auth;

import com.kalshi.betting.config.KalshiProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.PSSParameterSpec;
import java.util.Base64;

/**
 * Signs Kalshi API requests per their documented scheme: RSA-PSS (SHA-256, MGF1,
 * salt length = digest length) over the string {@code timestampMillis + httpMethod + requestPath},
 * where requestPath excludes the query string but includes the "/trade-api/v2" prefix.
 * <p>
 * Produces the three headers Kalshi expects on authenticated calls:
 * KALSHI-ACCESS-KEY, KALSHI-ACCESS-SIGNATURE, KALSHI-ACCESS-TIMESTAMP.
 */
@Component
public class KalshiRequestSigner {

    private static final Logger log = LoggerFactory.getLogger(KalshiRequestSigner.class);

    private final KalshiProperties properties;
    private PrivateKey privateKey;

    public KalshiRequestSigner(KalshiProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        String pem = resolvePem();
        if (pem == null || pem.isBlank()) {
            // Allow the app to start without credentials configured yet (e.g. first-run setup);
            // signing will fail loudly the moment an authenticated call is attempted.
            log.warn("Kalshi credentials not configured (KALSHI_API_KEY_ID / KALSHI_PRIVATE_KEY_PEM "
                    + "or KALSHI_PRIVATE_KEY_PATH are unset). Market browsing will work; placing bets, "
                    + "checking balance/positions, and viewing order books will fail until these are set.");
            return;
        }
        this.privateKey = loadPrivateKey(pem);
        if (properties.apiKeyId() == null || properties.apiKeyId().isBlank()) {
            log.warn("A Kalshi private key is configured but KALSHI_API_KEY_ID is not — "
                    + "authenticated requests will be rejected by Kalshi until it's set.");
        } else {
            log.info("Kalshi credentials configured (API key id: {}).", maskKeyId(properties.apiKeyId()));
        }
    }

    private static String maskKeyId(String keyId) {
        return keyId.length() <= 4 ? "****" : keyId.substring(0, 4) + "****";
    }

    private String resolvePem() {
        if (properties.privateKeyPem() != null && !properties.privateKeyPem().isBlank()) {
            return properties.privateKeyPem();
        }
        if (properties.privateKeyPath() != null && !properties.privateKeyPath().isBlank()) {
            try {
                return Files.readString(Path.of(properties.privateKeyPath()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to read Kalshi private key file at " + properties.privateKeyPath(), e);
            }
        }
        return null;
    }

    private static PrivateKey loadPrivateKey(String pem) {
        String sanitized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalStateException(
                    "Kalshi private key is in PKCS#1 (RSA) format. Convert it to PKCS#8 first, e.g.: "
                            + "openssl pkcs8 -topk8 -nocrypt -in rsa_key.pem -out pkcs8_key.pem");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(sanitized);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Kalshi RSA private key (expected PKCS#8 PEM)", e);
        }
    }

    public boolean isConfigured() {
        return privateKey != null;
    }

    /**
     * @param method      HTTP method, e.g. "GET", "POST", "DELETE"
     * @param requestPath path only (no host, no query string), e.g. "/trade-api/v2/portfolio/balance"
     */
    public SignedHeaders sign(String method, String requestPath) {
        if (privateKey == null) {
            throw new IllegalStateException(
                    "Kalshi API credentials are not configured. Set KALSHI_API_KEY_ID and "
                            + "KALSHI_PRIVATE_KEY_PEM (or KALSHI_PRIVATE_KEY_PATH).");
        }
        String timestamp = String.valueOf(System.currentTimeMillis());
        String message = timestamp + method.toUpperCase() + requestPath;
        String signature = signMessage(message);
        return new SignedHeaders(properties.apiKeyId(), signature, timestamp);
    }

    private String signMessage(String message) {
        try {
            Signature signature = Signature.getInstance("RSASSA-PSS");
            signature.setParameter(new PSSParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, PSSParameterSpec.TRAILER_FIELD_BC));
            signature.initSign(privateKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            byte[] signed = signature.sign();
            return Base64.getEncoder().encodeToString(signed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign Kalshi request", e);
        }
    }

    public record SignedHeaders(String accessKey, String signature, String timestamp) {
    }
}
