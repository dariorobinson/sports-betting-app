package com.kalshi.betting.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Optional shared-secret gate on {@code /api/**} (except the unauthenticated {@code /api/status}
 * health/config check). This is defense-in-depth on top of binding the server to loopback
 * ({@code server.address}) — the real control is not exposing this process to anything but a
 * trusted, co-located caller in the first place.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    private static final String HEADER = "X-App-Api-Key";

    private final String configuredKey;

    public ApiKeyFilter(@Value("${app.api-key:}") String configuredKey,
                         @Value("${server.address:127.0.0.1}") String serverAddress) {
        this.configuredKey = configuredKey;
        if ((configuredKey == null || configuredKey.isBlank())
                && !("127.0.0.1".equals(serverAddress) || "localhost".equals(serverAddress))) {
            log.warn("Server is bound to {} (not loopback) and APP_API_KEY is unset — this app's "
                    + "own REST API (including bet placement) is reachable by anything that can "
                    + "reach this host/port with no authentication. Set APP_API_KEY.", serverAddress);
        }
    }

    @PostConstruct
    void logStatus() {
        if (configuredKey == null || configuredKey.isBlank()) {
            log.info("APP_API_KEY not set — this app's own API is unauthenticated (fine on a "
                    + "loopback-only binding, not otherwise).");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean isApiRoute = path.startsWith("/api/") && !path.equals("/api/status");

        if (configuredKey == null || configuredKey.isBlank() || !isApiRoute) {
            chain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(HEADER);
        if (provided == null || !constantTimeEquals(configuredKey, provided)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"unauthorized\",\"message\":\"Missing or invalid " + HEADER + " header\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
