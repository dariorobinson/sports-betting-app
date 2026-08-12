# Sports mispricing pipeline (phase 2)

Compares the win-probability estimates produced by the **`sports-stats-analyst`** Claude Code
subagent (see [`.claude/agents/sports-stats-analyst.md`](../.claude/agents/sports-stats-analyst.md))
against **live Kalshi prices**, and reports where they disagree — i.e. potentially mispriced,
positive-EV markets, ranked best-first.

This is **advisory only**. It:
- never authenticates to Kalshi (market data is public — no signing, no credentials),
- never touches the Spring Boot betting app, and
- never places a bet.

It's a self-contained local script — Python 3 **stdlib only**, no `pip install`, no build step. (It
falls back to the system `curl` if your local Python has no working CA bundle, which is common on
macOS python.org builds.)

## Workflow

```
sports-stats-analyst subagent            tools/mispricing_pipeline.py
──────────────────────────────           ────────────────────────────
researches a game/player  ──writes──▶     data/sports-stats/*.json
(MLB API, Odds API, ESPN,                        │
 power ratings, ...)                             ▼
                                          pulls current Kalshi price per market
                                                 │
                                                 ▼
                                          ranked +EV report (stdout + a .md file
                                          under data/sports-stats/reports/)
```

1. **Produce estimates.** Ask the `sports-stats-analyst` subagent to research one or more
   games/players and persist its estimates. Each estimate is one JSON file in `data/sports-stats/`
   following the schema below. (The analyst can look up the Kalshi ticker itself — the public API
   at `https://api.elections.kalshi.com/trade-api/v2/events?series_ticker=...&with_nested_markets=true`
   lists every market and its ticker.)

2. **Run the pipeline.**
   ```bash
   python3 tools/mispricing_pipeline.py
   ```
   Common options:
   ```bash
   python3 tools/mispricing_pipeline.py --min-ev 0.05      # only flag edges ≥ 5c/contract
   python3 tools/mispricing_pipeline.py --no-report        # stdout only, don't write a .md
   python3 tools/mispricing_pipeline.py --data-dir some/other/dir
   ```

3. **Read the report.** Markets are grouped into: **Actionable** (EV ≥ threshold), **Slight edge**
   (positive but below threshold), and **No edge** (model agrees with or trails the market).
   Anything unresolvable is listed under **Skipped** with the reason.

## Input schema (one estimate per JSON file)

```json
{
  "schema_version": 1,
  "query": "Phoenix Mercury vs Los Angeles Sparks, Aug 11 2026 — Sparks win probability",
  "sport": "basketball",
  "league": "wnba",
  "event_description": "Phoenix Mercury vs Los Angeles Sparks",
  "event_date": "2026-08-11",
  "kalshi": {
    "event_ticker": "KXWNBAGAME-26AUG11PHXLA",
    "market_ticker": "KXWNBAGAME-26AUG11PHXLA-LA",
    "outcome_label": "Los Angeles Sparks win"
  },
  "estimate": {
    "outcome": "yes",
    "probability": 0.63,
    "reasoning": "which stats/ratings were weighted and why"
  },
  "data": [
    {"metric": "home net rating", "value": "+4.1", "source_url": "https://...", "as_of": "2026-08-11"}
  ],
  "confidence": "medium",
  "gaps": "no injury/rest data pulled",
  "fetched_at": "2026-08-11T18:00:00Z"
}
```

**Required for the pipeline:**
- `kalshi.market_ticker` — the exact Kalshi market being estimated (authoritative). If you only
  provide `kalshi.event_ticker`, the pipeline can resolve it **only** when that event has a single
  market; otherwise it skips and asks for the explicit market ticker (it will not guess which side).
- `estimate.outcome` — `"yes"` or `"no"`: which side of that Kalshi market your probability is for.
- `estimate.probability` — a number in `[0, 1]`: your probability that `outcome` resolves true.

Everything else is for sourcing/transparency/freshness and is echoed into the report.

## How the numbers are computed

- **Implied probability** of a Kalshi outcome ≈ its price in dollars (a market priced at `$0.55`
  implies ~55%). The pipeline reads `yes_ask_dollars` / `yes_bid_dollars` (or the `no_` equivalents).
- **EV/contract** = `model_probability − ask`. You pay the ask now and receive $1 if the outcome
  resolves true, so this is the expected profit per $1 contract *if the model probability is right*.
  This is the actionable number (it accounts for the spread you actually cross).
- **Edge vs mid** = `model_probability − bid/ask midpoint`. A "fairness" signal that ignores the
  spread — useful for spotting a genuinely mispriced line vs. one that only looks off because of a
  wide spread.

## Files

- `mispricing_pipeline.py` — the pipeline.
- `../data/sports-stats/*.json` — analyst estimates (input). Files prefixed `EXAMPLE-` are
  illustrative fixtures with made-up model probabilities; delete or overwrite them with real runs.
- `../data/sports-stats/reports/mispricing-*.md` — generated reports (output).
