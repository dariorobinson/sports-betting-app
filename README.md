# Kalshi Sports Betting

A Spring Boot app for browsing Kalshi sports event markets and placing bets, built against
Kalshi's Trade API v2 (`openapi.yaml` in this repo, downloaded from https://docs.kalshi.com/openapi.yaml).

It does **not** use the AsyncAPI (WebSocket) spec — everything here is REST/polling. If you
later want live order-book/ticker streaming, that's the natural next addition (see
`asyncapi.yaml`).

⚠️ **This places real orders with real money on the configured Kalshi environment.** Point it at
the demo environment first (see below) until you're confident in it.

Deploying to a server? See [DEPLOY.md](DEPLOY.md) (jar + systemd on EC2).

## How it works

- **Auth**: Kalshi requires every private-endpoint request to carry three headers —
  `KALSHI-ACCESS-KEY`, `KALSHI-ACCESS-TIMESTAMP`, `KALSHI-ACCESS-SIGNATURE` — where the signature
  is an RSA-PSS (SHA-256) signature over `timestamp + HTTP_METHOD + request_path`, signed with
  your API key's private key. That's implemented in
  [`KalshiRequestSigner`](src/main/java/com/kalshi/betting/auth/KalshiRequestSigner.java).
- **Betting semantics**: Kalshi's order API is expressed in terms of the YES leg only
  (`bid` = buy YES, `ask` = sell YES / buy NO, at a YES-denominated price). This app lets you
  think in plain "buy YES" / "buy NO" terms instead; the translation lives in
  [`BettingService`](src/main/java/com/kalshi/betting/service/BettingService.java).

## 1. Get Kalshi API credentials

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

Browsing markets works even without credentials configured (those endpoints are public); placing
bets, checking your balance/positions, and viewing order books all require them.

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

Then open http://localhost:8080 for a minimal browser UI to browse sports, view games/markets,
and place bets. Or use the REST API directly:

```bash
# Browse
curl localhost:8080/api/sports
curl localhost:8080/api/sports/KXNFLGAME/games
curl localhost:8080/api/sports/games/KXNFLGAME-25JAN01DALPHI
curl "localhost:8080/api/sports/markets/KXNFLGAME-25JAN01DALPHI-DAL/orderbook"

# Portfolio (requires credentials)
curl localhost:8080/api/portfolio/balance
curl localhost:8080/api/portfolio/positions

# Place a bet: buy 5 contracts of YES at 55 cents
curl -X POST localhost:8080/api/bets \
  -H "Content-Type: application/json" \
  -d '{"ticker":"KXNFLGAME-25JAN01DALPHI-DAL","outcome":"YES","action":"BUY","price":0.55,"count":5}'

# List your orders / cancel one
curl localhost:8080/api/bets
curl -X DELETE localhost:8080/api/bets/{orderId}
```

### Placing a bet

`POST /api/bets`:

| Field         | Type   | Notes                                                             |
|---------------|--------|--------------------------------------------------------------------|
| `ticker`      | string | Market ticker (from `/api/sports/games/{eventTicker}`)             |
| `outcome`     | string | `"YES"` or `"NO"` — which side you're betting on                  |
| `action`      | string | `"BUY"` (default) to open a position, `"SELL"` to close/reduce one |
| `price`       | number | Price of that outcome in dollars, e.g. `0.55` for 55¢              |
| `count`       | int    | Number of contracts                                                 |
| `timeInForce` | string | `good_till_canceled` (default), `immediate_or_cancel`, `fill_or_kill` |

### Browsing and pricing combos ("parlays")

Kalshi calls these **multivariate event collections** — a collection defines a slate of eligible
legs (games/props) plus a min/max leg count; you don't get a price until you tell Kalshi exactly
which legs+sides you want, which materializes (or looks up) a real market for that combination.

```bash
# List open combo collections that include at least one sports leg
curl localhost:8080/api/combos

# See what legs are available. Collections can have hundreds of legs across many series/sports —
# if there are too many to show at once, this returns a per-series leg count instead of a dump;
# pass ?seriesTicker=<one of those> to drill into a manageable subset with live prices.
curl "localhost:8080/api/combos/{collectionTicker}/legs"
curl "localhost:8080/api/combos/{collectionTicker}/legs?seriesTicker=KXNBASUMMERGAME"

# Materialize the real Kalshi price for a specific set of legs. This creates/looks up a listing —
# it does NOT place an order or risk money — but does count against Kalshi's 5,000/week
# collection-market-creation limit.
curl -X POST "localhost:8080/api/combos/{collectionTicker}/price" \
  -H "Content-Type: application/json" \
  -d '{
    "legs": [
      {"event_ticker": "KXNBASUMMERGAME-...", "market_ticker": "KXNBASUMMERGAME-...-DEN", "side": "YES"},
      {"event_ticker": "KXNBASUMMERGAME-...", "market_ticker": "KXNBASUMMERGAME-...-GSW", "side": "YES"}
    ]
  }'
```

This app deliberately doesn't estimate win probability or rank candidate combos itself — that
needs external stats/judgment (see the `sports-stats-analyst` agent in `.claude/agents/`), which
isn't something this Java app can invoke on its own. The workflow is: an agent pulls legs from
here, estimates each leg's probability, picks promising combinations, and calls the `/price`
endpoint to see Kalshi's actual price for the ones worth surfacing.

## Project layout

```
config/     Kalshi connection properties, RestClient bean, API key filter
auth/       RSA-PSS request signing
client/     Low-level typed Kalshi API client + DTOs mirroring openapi.yaml
service/    Sports catalog browsing, bet placement/translation, portfolio, combo pricing
web/        This app's own REST API + DTOs
```

## Notes / limitations

- Covers sports market discovery (series → events/games → markets), order books, placing/
  cancelling limit orders (Kalshi's V2 order endpoint), balance, positions, and browsing/pricing
  combo (multivariate event) markets — not the full Kalshi API surface (RFQs, subaccounts, FCM,
  block trades, etc.).
- Uses the V2 order endpoints (`/portfolio/events/orders`) since the legacy `/portfolio/orders`
  endpoints are being phased out per Kalshi's own spec.
- No persistence/database — it's a thin, stateless pass-through to Kalshi.
