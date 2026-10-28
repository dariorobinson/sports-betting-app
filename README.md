# Kalshi Sports Value-Play Research Assistant

A Spring Boot app that researches sports betting value on Kalshi and reports it — it does **not**
place, cancel, or modify any bet. It compares real statistics (ESPN + web search) against Kalshi's
live market prices to find plays where the data suggests a meaningfully better true probability than
Kalshi's price implies, across College Football, Soccer, NFL, and NBA (player props). Built against
Kalshi's Trade API v2 (`openapi.yaml` in this repo) for **market data only** — browsing series,
events, markets, and order books, all public/read-only endpoints.

Deploying to a server? See [DEPLOY.md](DEPLOY.md) (jar + systemd on EC2).

## How it works

- Every morning (9am America/Chicago by default), [`ValuePlayScheduler`](src/main/java/com/kalshi/betting/scheduler/ValuePlayScheduler.java)
  runs four independent research tasks — one per sport (CFB, Soccer, NFL, NBA player props) — each
  letting Claude browse Kalshi's live markets, pull real stats from ESPN
  ([`sportsdata/`](src/main/java/com/kalshi/betting/sportsdata/)) and the web, and write up 2-3
  value plays with the stats behind them. The combined report is DMed to one pre-authorized Discord
  user. Nothing is ever placed — see [`docs/instructions.md`](src/main/resources/docs/instructions.md)
  for the exact methodology/format the model follows.
- You can also just DM the bot directly any time ("find me some value plays in the NBA tonight",
  "what's Kalshi pricing the Chiefs game at") via the same Discord bot (see below).
- **Auth**: Kalshi requires every private-endpoint request (order books, settlement history) to
  carry three headers — `KALSHI-ACCESS-KEY`, `KALSHI-ACCESS-TIMESTAMP`, `KALSHI-ACCESS-SIGNATURE` —
  where the signature is an RSA-PSS (SHA-256) signature over `timestamp + HTTP_METHOD + request_path`,
  signed with your API key's private key. That's implemented in
  [`KalshiRequestSigner`](src/main/java/com/kalshi/betting/auth/KalshiRequestSigner.java). Market
  browsing itself (series/events/markets) is public and needs no credentials at all.

## 1. Get Kalshi API credentials (optional, but needed for order books and P&L tracking)

1. Create a Kalshi account (production: https://kalshi.com, demo: https://demo.kalshi.co).
2. In account settings, generate an API key. Kalshi will show you an **API Key ID** and have you
   generate an RSA key pair — save the **private key PEM**, Kalshi only shows it once.
3. If your private key is in PKCS#1 format (`-----BEGIN RSA PRIVATE KEY-----`), convert it to
   PKCS#8 (what this app expects):
   ```bash
   openssl pkcs8 -topk8 -nocrypt -in rsa_key.pem -out pkcs8_key.pem
   ```

## 2. Configure the app

Set these environment variables before starting the app:

```bash
export KALSHI_API_KEY_ID="your-api-key-id"
export KALSHI_PRIVATE_KEY_PATH="/path/to/pkcs8_key.pem"   # or KALSHI_PRIVATE_KEY_PEM="$(cat pkcs8_key.pem)"

# Demo environment (paper trading) — recommended for first runs:
export KALSHI_BASE_URL="https://demo-api.kalshi.co/trade-api/v2"

# Production:
# export KALSHI_BASE_URL="https://api.elections.kalshi.com/trade-api/v2"
```

Browsing markets (series/events/games) works even without credentials configured — those endpoints
are public. Order books and settlement/P&L history require them.

### Other environment variables

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `SERVER_ADDRESS` | `127.0.0.1` | Bind address. **Leave this on loopback for any deployed environment** — this app's own API has no auth by default, so only a co-located process (e.g. a Discord bot on the same box) should be able to reach it. Only widen this behind your own reverse proxy / security-group rules. |
| `APP_API_KEY` | unset | If set, requires a matching `X-App-Api-Key` header on all `/api/**` calls except `/api/status`. Defense-in-depth on top of the loopback binding, not a replacement for it. |
| `KALSHI_CONNECT_TIMEOUT` / `KALSHI_READ_TIMEOUT` | `10s` / `30s` | Timeouts for calls to Kalshi. Steady-state calls finish in well under a second; these are generous enough to survive a cold JVM's first HTTPS call without tripping. |

Hit `GET /api/status` any time (no key needed) to confirm what a running instance is actually
configured with — useful right after a deploy.

## 3. Run it

```bash
./mvnw spring-boot:run
```

Then open http://localhost:8080 for a minimal browser UI to browse sports and games. Or use the
REST API directly:

```bash
# Browse
curl localhost:8080/api/sports
curl localhost:8080/api/sports/KXNFLGAME/games
curl localhost:8080/api/sports/games/KXNFLGAME-25JAN01DALPHI
curl "localhost:8080/api/sports/markets/KXNFLGAME-25JAN01DALPHI-DAL/orderbook"

# Realized P&L from Kalshi's own settlement history (today/week/month)
curl localhost:8080/api/pnl

# Manually trigger the daily value-play research + Discord report on demand
curl -X POST localhost:8080/api/scheduler/value-plays/run
```

## Discord bot

This app runs its own Discord bot in-process — no external CLI/agent session required. It uses
**JDA** to connect to Discord's gateway directly and the **Anthropic Java SDK** to run an agentic
tool-calling loop over the `sportsdata`/`SportsCatalogService` layer, plus Anthropic's server-side
web search tool for anything ESPN's structured data doesn't cover.

- **Access control**: only DMs from one pre-authorized Discord user ID are acted on — everyone
  else, and all server/channel messages, are silently ignored. See
  [`DiscordListener`](src/main/java/com/kalshi/betting/discord/DiscordListener.java).
- **Read-only, advisory only**: there is no tool anywhere in this app that places, cancels, or
  modifies a Kalshi order. The model can only browse markets, pull ESPN stats, and search the web.
- **Per-user conversation history** is kept in memory only (`OrchestratorService`) — it resets on
  restart. The scheduled daily report runs stateless (no memory of past reports), by design — see
  the javadoc on `OrchestratorService.chatOnce`.

### Setup

```bash
export DISCORD_BOT_TOKEN="your-bot-token"                # Discord Developer Portal -> your app -> Bot
export DISCORD_AUTHORIZED_USER_ID="your-discord-user-id"  # enable Developer Mode -> right-click yourself -> Copy User ID
export ANTHROPIC_API_KEY="your-anthropic-api-key"         # console.anthropic.com
```

Without `DISCORD_BOT_TOKEN` set, the bot simply doesn't start — the REST API and web UI work
exactly as before with no Discord dependency at all (check the startup log for a clear message
either way, no silent failure).

Tool classes live in `orchestrator/tool/` — each is a thin adapter (`Supplier<String>` + Jackson
annotations for the model-facing schema) over one service method: `ListSportsTool`, `ListGamesTool`,
`GetGameTool`, `GetMarketOrderbookTool` (Kalshi market data), `GetTeamAnalyticsTool`,
`GetIndividualAnalyticsTool`, `GetTeamRecentFormTool`, `GetPlayerRecentStatsTool` (ESPN stats).

Dependency versions (`com.anthropic:anthropic-java:2.17.0`, `net.dv8tion:JDA:5.3.0`) are matched
to a proven-working combination rather than latest — JDA in particular has a major version line
(6.x) with unverified compatibility here.

## Project layout

```
config/        Kalshi connection properties, RestClient bean, API key filter, Anthropic client bean
auth/          RSA-PSS request signing
client/        Low-level typed Kalshi API client (read-only market data + settlements) + DTOs
service/       Sports catalog browsing (series -> events -> markets), P&L tracking
sportsdata/    ESPN-backed team/player analytics (standings, head-to-head, recent form, box scores)
web/           This app's own REST API + DTOs
discord/       JDA-based Discord bot (gateway connection, DM/allowlist gate)
scheduler/     Daily value-play report (research + Discord DM), P&L summaries
orchestrator/  Anthropic tool-calling loop + tool classes wrapping the sportsdata/service layers
```

Note on Spring Boot 4.1.0: it split `RestClient`/`RestClientAutoConfiguration` out of
`spring-boot-starter-web` into its own module — `spring-boot-starter-restclient` is required
explicitly, or the `RestClient.Builder` bean (and with it, correct snake_case JSON parsing from
Kalshi) won't exist. Also, its auto-configured `ObjectMapper` is the newer Jackson 3.x
(`tools.jackson`) flavor, a different class than the classic `com.fasterxml.jackson.databind.ObjectMapper`
— that's why `ToolServices` constructs its own plain instance rather than injecting Spring's.

## Notes / limitations

- Covers sports market discovery (series → events/games → markets) and order books, plus realized
  P&L from settlement history — exposed both as a REST API and via the Discord bot's tool set — not
  the full Kalshi API surface. There is deliberately no order-placement, combo/RFQ, or
  portfolio-balance capability anywhere in this app anymore.
- `tools/mispricing_pipeline.py` is a separate, standalone local script (Python stdlib only) that
  compares `sports-stats-analyst` subagent estimates against live Kalshi prices — unrelated to the
  Discord bot's own research flow, see [`tools/README.md`](tools/README.md).
- No persistence/database — it's a thin, stateless pass-through to Kalshi and ESPN.
