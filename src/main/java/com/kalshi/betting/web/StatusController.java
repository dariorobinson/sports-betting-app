package com.kalshi.betting.web;

import com.kalshi.betting.auth.KalshiRequestSigner;
import com.kalshi.betting.config.KalshiProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Runtime config self-check — hit this right after deploying to confirm environment
 * variables landed correctly, without needing to dig through logs.
 */
@RestController
public class StatusController {

    private final KalshiRequestSigner signer;
    private final KalshiProperties properties;

    public StatusController(KalshiRequestSigner signer, KalshiProperties properties) {
        this.signer = signer;
        this.properties = properties;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return Map.of(
                "kalshiBaseUrl", properties.baseUrl(),
                "kalshiCredentialsConfigured", signer.isConfigured()
        );
    }
}
