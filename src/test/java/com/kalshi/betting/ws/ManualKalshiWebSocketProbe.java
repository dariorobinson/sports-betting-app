package com.kalshi.betting.ws;

import com.kalshi.betting.auth.KalshiRequestSigner;
import com.kalshi.betting.config.KalshiProperties;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Manual, human-watched diagnostic — NOT a JUnit test (needs ~90s against the REAL Kalshi
 * WebSocket endpoint with real credentials, and a human watching the output). Run directly (IDE
 * "Run main()", or {@code mvn -q compile test-compile exec:java
 * -Dexec.mainClass=com.kalshi.betting.ws.ManualKalshiWebSocketProbe -Dexec.classpathScope=test})
 * with these env vars set: {@code KALSHI_API_KEY_ID}, and either {@code KALSHI_PRIVATE_KEY_PEM} or
 * {@code KALSHI_PRIVATE_KEY_PATH}. Optionally {@code KALSHI_WS_URL} to point at a non-default
 * endpoint (e.g. a demo environment, if one exists with WebSocket support — check before spending
 * this against production credentials).
 * <p>
 * Purpose: confirm the one fact Kalshi's docs don't spell out — the exact path string that must be
 * signed for the WebSocket upgrade handshake — and observe real {@code quote_executed} /
 * {@code market_position} frames to replace the doc-sourced fixtures in
 * {@link WsMessageParsingTest} with real captured traffic.
 * <p>
 * Uses the REAL {@link KalshiRequestSigner} — via reflection to invoke its package-private
 * {@code init()} (normally a Spring {@code @PostConstruct}, inaccessible from this package
 * otherwise) — so this exercises exactly the signing code that ships to production, not a
 * reimplementation of it.
 */
public final class ManualKalshiWebSocketProbe {

    private static final Duration OBSERVE_DURATION = Duration.ofSeconds(90);

    public static void main(String[] args) throws Exception {
        String apiKeyId = require("KALSHI_API_KEY_ID");
        String privateKeyPem = System.getenv("KALSHI_PRIVATE_KEY_PEM");
        String privateKeyPath = System.getenv("KALSHI_PRIVATE_KEY_PATH");
        if (isBlank(privateKeyPem) && isBlank(privateKeyPath)) {
            throw new IllegalStateException("Set KALSHI_PRIVATE_KEY_PEM or KALSHI_PRIVATE_KEY_PATH");
        }
        String wsUrl = System.getenv().getOrDefault("KALSHI_WS_URL",
                "wss://external-api-ws.kalshi.com/trade-api/ws/v2");

        KalshiProperties properties = new KalshiProperties(
                "https://api.elections.kalshi.com/trade-api/v2", // unused here — this probe never calls REST
                wsUrl, true, apiKeyId, privateKeyPem, privateKeyPath);
        KalshiRequestSigner signer = new KalshiRequestSigner(properties);
        invokeInit(signer);
        if (!signer.isConfigured()) {
            throw new IllegalStateException("Signer did not configure — check credentials");
        }

        List<String> candidatePaths = List.of(
                URI.create(wsUrl).getPath(), // expected: "/trade-api/ws/v2"
                "/trade-api/v2",
                "");
        for (String path : candidatePaths) {
            System.out.println("=== Attempting handshake with signed path: \"" + path + "\" ===");
            if (attempt(signer, wsUrl, path)) {
                return;
            }
        }
        System.out.println("All candidate paths failed — inspect the logged failure causes above.");
    }

    private static boolean attempt(KalshiRequestSigner signer, String wsUrl, String signedPath) throws Exception {
        KalshiRequestSigner.SignedHeaders signed = signer.sign("GET", signedPath);
        HttpClient httpClient = HttpClient.newHttpClient();
        CountDownLatch closed = new CountDownLatch(1);

        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                System.out.println("[OPEN] Handshake succeeded with path \"" + signedPath + "\"");
                String subscribeJson = """
                        {"id":1,"cmd":"subscribe","params":{"channels":["communications","market_positions"]}}""";
                webSocket.sendText(subscribeJson, true);
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    System.out.println("[FRAME] " + buffer);
                    buffer.setLength(0);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
                System.out.println("[PING] received, replying with pong");
                webSocket.sendPong(message);
                webSocket.request(1);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                System.out.println("[ERROR] " + error);
                closed.countDown();
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                System.out.println("[CLOSE] " + statusCode + " " + reason);
                closed.countDown();
                return null;
            }
        };

        WebSocket socket;
        try {
            socket = httpClient.newWebSocketBuilder()
                    .header("KALSHI-ACCESS-KEY", signed.accessKey())
                    .header("KALSHI-ACCESS-SIGNATURE", signed.signature())
                    .header("KALSHI-ACCESS-TIMESTAMP", signed.timestamp())
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(wsUrl), listener)
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.out.println("[FAILED] path \"" + signedPath + "\": " + e);
            return false;
        }

        System.out.println("Observing for " + OBSERVE_DURATION.toSeconds() + "s — place/watch a combo bet now "
                + "if you want to see a real quote_executed or market_position(closed) frame.");
        boolean closedEarly = closed.await(OBSERVE_DURATION.toSeconds(), TimeUnit.SECONDS);
        if (!closedEarly) {
            System.out.println("Observation window elapsed without the connection dropping — looks healthy.");
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "probe complete");
        }
        return true;
    }

    private static void invokeInit(KalshiRequestSigner signer) throws Exception {
        Method init = KalshiRequestSigner.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(signer);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String require(String envVar) {
        String value = System.getenv(envVar);
        if (isBlank(value)) {
            throw new IllegalStateException("Missing required env var: " + envVar);
        }
        return value;
    }

    private ManualKalshiWebSocketProbe() {
    }
}
