package com.kalshi.betting.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.kalshi.betting.auth.KalshiRequestSigner;
import com.kalshi.betting.config.KalshiProperties;
import com.kalshi.betting.service.ActiveComboLegTracker;
import com.kalshi.betting.ws.dto.MarketPositionMsg;
import com.kalshi.betting.ws.dto.QuoteExecutedMsg;
import com.kalshi.betting.ws.dto.SubscribeCommand;
import com.kalshi.betting.ws.dto.WsEnvelope;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persistent connection to Kalshi's WebSocket API — a pure accelerant for two signals that
 * otherwise only arrive via REST polling: {@code quote_executed} events (feeds
 * {@link QuoteExecutionSignal}, raced against {@code ComboService}'s REST poll) and
 * {@code market_position} updates (prunes {@link ActiveComboLegTracker} the instant a tracked
 * combo's position closes, instead of waiting for the next {@code GetPositionsTool} call).
 * <p>
 * Every signal this provides has a working REST-based fallback — if this connection is disabled,
 * unconfigured, or misbehaving, the rest of the app functions exactly as it did before this class
 * existed. Startup is non-blocking and never fails app boot (mirrors {@code DiscordConfig}'s
 * graceful-absence philosophy for an optional external connection).
 */
@Component
public class KalshiWebSocketClient implements WebSocket.Listener {

    private static final Logger log = LoggerFactory.getLogger(KalshiWebSocketClient.class);

    private static final List<String> SUBSCRIBED_CHANNELS = List.of("communications", "market_positions");
    private static final long INITIAL_BACKOFF_MILLIS = 1_000;
    private static final long MAX_BACKOFF_MILLIS = 60_000;
    private static final int WATCHDOG_INTERVAL_SECONDS = 15;
    /** 3x Kalshi's documented 10s ping cadence — a stale connection this old is treated as dead. */
    private static final int WATCHDOG_STALE_SECONDS = 30;

    private final KalshiProperties properties;
    private final KalshiRequestSigner signer;
    private final QuoteExecutionSignal quoteExecutionSignal;
    private final ActiveComboLegTracker activeComboLegTracker;

    /** Deliberately NOT the Spring-managed Jackson ObjectMapper — decoupled and explicit, same
     *  reasoning as {@link ActiveComboLegTracker}'s own mapper, but configured to match Kalshi's
     *  snake_case wire format explicitly rather than relying on application.yml's global setting. */
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "kalshi-ws-scheduler"));

    private final AtomicReference<WebSocket> currentSocket = new AtomicReference<>();
    private final AtomicReference<Instant> lastMessageAt = new AtomicReference<>();
    private final AtomicLong backoffMillis = new AtomicLong(INITIAL_BACKOFF_MILLIS);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicInteger nextCommandId = new AtomicInteger(1);
    private final StringBuilder textBuffer = new StringBuilder();
    private volatile boolean shuttingDown = false;

    public KalshiWebSocketClient(KalshiProperties properties, KalshiRequestSigner signer,
                                   QuoteExecutionSignal quoteExecutionSignal,
                                   ActiveComboLegTracker activeComboLegTracker) {
        this.properties = properties;
        this.signer = signer;
        this.quoteExecutionSignal = quoteExecutionSignal;
        this.activeComboLegTracker = activeComboLegTracker;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.wsEnabled() || !signer.isConfigured()) {
            log.warn("Kalshi WebSocket disabled or credentials not configured — skipping connection; "
                    + "combo execution confirmation and leg pruning will rely on REST polling only.");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::checkLiveness,
                WATCHDOG_INTERVAL_SECONDS, WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
        connect();
    }

    private void connect() {
        if (shuttingDown || !properties.wsEnabled() || !signer.isConfigured()) {
            return;
        }
        URI wsUri = URI.create(properties.wsUrl());
        KalshiRequestSigner.SignedHeaders signed;
        try {
            signed = signer.sign("GET", wsUri.getPath());
        } catch (Exception e) {
            log.error("Failed to sign Kalshi WebSocket handshake — will retry: {}", e.getMessage());
            scheduleReconnect();
            return;
        }
        log.info("Connecting to Kalshi WebSocket at {}", properties.wsUrl());
        httpClient.newWebSocketBuilder()
                .header("KALSHI-ACCESS-KEY", signed.accessKey())
                .header("KALSHI-ACCESS-SIGNATURE", signed.signature())
                .header("KALSHI-ACCESS-TIMESTAMP", signed.timestamp())
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(wsUri, this)
                .exceptionally(e -> {
                    log.warn("Kalshi WebSocket connection attempt failed: {}", e.getMessage());
                    scheduleReconnect();
                    return null;
                });
    }

    // ---- WebSocket.Listener ----

    @Override
    public void onOpen(WebSocket webSocket) {
        log.info("Kalshi WebSocket connected");
        currentSocket.set(webSocket);
        backoffMillis.set(INITIAL_BACKOFF_MILLIS);
        lastMessageAt.set(Instant.now());
        textBuffer.setLength(0);
        try {
            String json = mapper.writeValueAsString(
                    SubscribeCommand.subscribe(nextCommandId.getAndIncrement(), SUBSCRIBED_CHANNELS));
            webSocket.sendText(json, true);
        } catch (Exception e) {
            log.error("Failed to send Kalshi WebSocket subscribe command", e);
        }
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        lastMessageAt.set(Instant.now());
        textBuffer.append(data);
        if (last) {
            String message = textBuffer.toString();
            textBuffer.setLength(0);
            handleMessage(message);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        lastMessageAt.set(Instant.now());
        webSocket.sendPong(message);
        webSocket.request(1);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        log.warn("Kalshi WebSocket error: {}", error.getMessage());
        scheduleReconnect();
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        log.warn("Kalshi WebSocket closed: {} {}", statusCode, reason);
        scheduleReconnect();
        return null;
    }

    // ---- Message dispatch ----

    private void handleMessage(String message) {
        try {
            WsEnvelope envelope = mapper.readValue(message, WsEnvelope.class);
            String type = envelope.type() == null ? "" : envelope.type();
            switch (type) {
                case "quote_executed" -> {
                    QuoteExecutedMsg msg = mapper.treeToValue(envelope.msg(), QuoteExecutedMsg.class);
                    log.info("Kalshi WebSocket quote_executed: {} (raw: {})", msg, message);
                    quoteExecutionSignal.complete(msg);
                }
                case "market_position" -> {
                    MarketPositionMsg msg = mapper.treeToValue(envelope.msg(), MarketPositionMsg.class);
                    log.info("Kalshi WebSocket market_position: {} (raw: {})", msg, message);
                    if (isClosed(msg.positionFp())) {
                        activeComboLegTracker.pruneIfClosed(msg.marketTicker());
                    }
                }
                // Logged with the full raw frame (not just the parsed fields we expected) while this
                // integration is new — Kalshi's docs don't fully spell out this envelope's shape
                // (e.g. sid came back null on a real "subscribed" ack, which the docs don't explain),
                // so seeing the actual bytes is more useful than a summary until that's understood.
                case "subscribed" -> log.info("Kalshi WebSocket subscribed: {}", message);
                case "error" -> log.warn("Kalshi WebSocket error frame: {}", message);
                default -> log.info("Unhandled Kalshi WebSocket message type={}: {}", envelope.type(), message);
            }
        } catch (Exception e) {
            log.warn("Failed to parse Kalshi WebSocket message, ignoring: {} ({})", message, e.getMessage());
        }
    }

    private static boolean isClosed(String positionFp) {
        if (positionFp == null) {
            return false;
        }
        try {
            return new BigDecimal(positionFp).compareTo(BigDecimal.ZERO) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---- Reconnect / watchdog ----

    private void scheduleReconnect() {
        if (shuttingDown) {
            return;
        }
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        long base = backoffMillis.getAndUpdate(prev -> Math.min(prev * 2, MAX_BACKOFF_MILLIS));
        double jitterFactor = 1 + (ThreadLocalRandom.current().nextDouble() * 0.4 - 0.2);
        long delay = Math.max(0, (long) (base * jitterFactor));
        log.info("Reconnecting to Kalshi WebSocket in {}ms", delay);
        scheduler.schedule(() -> {
            reconnectScheduled.set(false);
            connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void checkLiveness() {
        if (shuttingDown) {
            return;
        }
        WebSocket socket = currentSocket.get();
        Instant last = lastMessageAt.get();
        if (socket == null || last == null) {
            return;
        }
        if (Duration.between(last, Instant.now()).getSeconds() > WATCHDOG_STALE_SECONDS) {
            log.warn("Kalshi WebSocket appears stale (no message in over {}s) — forcing reconnect",
                    WATCHDOG_STALE_SECONDS);
            socket.abort();
            scheduleReconnect();
        }
    }

    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        scheduler.shutdownNow();
        WebSocket socket = currentSocket.get();
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
    }
}
