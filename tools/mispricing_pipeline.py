#!/usr/bin/env python3
"""
Mispricing pipeline (phase 2 of the sports-stats project).

Reads the win-probability estimates that the `sports-stats-analyst` Claude Code subagent writes to
`data/sports-stats/*.json`, pulls the CURRENT Kalshi price for each referenced market, and reports
where the analyst's model probability disagrees with the market — i.e. potentially mispriced /
positive-EV markets, ranked best-first.

This is ADVISORY ONLY. It never authenticates to Kalshi, never touches the betting app, and never
places a bet. Kalshi market data (`/events`, `/markets`) is public and needs no signing, so this is
a self-contained local script (stdlib only — no pip installs, no build step).

Usage:
    python3 tools/mispricing_pipeline.py
    python3 tools/mispricing_pipeline.py --data-dir data/sports-stats --min-ev 0.03
    python3 tools/mispricing_pipeline.py --no-report        # stdout only, don't write a .md file

Input JSON schema (one estimate per file) — see tools/README.md and the analyst agent definition:
    {
      "schema_version": 1,
      "query": "<what was asked>",
      "sport": "baseball", "league": "mlb",
      "event_description": "Baltimore Orioles vs Minnesota Twins",
      "event_date": "2026-08-11",
      "kalshi": {
        "event_ticker": "KXMLBGAME-26AUG111940BALMIN",
        "market_ticker": "KXMLBGAME-26AUG111940BALMIN-BAL",   # optional if event_ticker + side given
        "outcome_label": "Baltimore Orioles win"
      },
      "estimate": { "outcome": "yes", "probability": 0.58, "reasoning": "..." },
      "data": [ {"metric": "...", "value": "...", "source_url": "...", "as_of": "..."} ],
      "confidence": "medium", "gaps": "...", "fetched_at": "2026-08-11T14:03:00Z"
    }

`estimate.probability` is the analyst's probability (0..1) that `estimate.outcome` (yes/no on that
Kalshi market) resolves true. The pipeline compares it against the live market price.
"""

import argparse
import datetime as dt
import glob
import json
import os
import ssl
import subprocess
import sys
import urllib.error
import urllib.request

DEFAULT_BASE_URL = "https://api.elections.kalshi.com/trade-api/v2"
DEFAULT_DATA_DIR = "data/sports-stats"
DEFAULT_MIN_EV = 0.03  # $0.03 expected value per $1 contract = a 3-cent edge; below this = noise.
HTTP_TIMEOUT_S = 15


def log(msg):
    print(msg, file=sys.stderr)


# ---------------------------------------------------------------------------
# Kalshi (public market data — no auth)
# ---------------------------------------------------------------------------

class NotFound(Exception):
    """HTTP 404 from Kalshi — market/event doesn't exist (or has closed)."""


def _http_get_json(url):
    """
    GET a public Kalshi JSON endpoint. Tries Python's urllib first; if the local Python has no
    working CA bundle (a common macOS python.org situation — `CERTIFICATE_VERIFY_FAILED`), transparently
    falls back to the system `curl`, which uses the OS trust store. Never disables verification.
    Raises NotFound on 404.
    """
    req = urllib.request.Request(url, headers={"Accept": "application/json",
                                               "User-Agent": "sports-mispricing-pipeline/1"})
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            raise NotFound(url)
        raise
    except (ssl.SSLError, urllib.error.URLError) as e:
        reason = getattr(e, "reason", e)
        if isinstance(reason, ssl.SSLError) or isinstance(e, ssl.SSLError):
            return _curl_get_json(url)
        raise


def _curl_get_json(url):
    """
    Fallback fetch via system curl (uses the OS CA store). Captures the HTTP status explicitly (via
    -w) rather than relying on curl's -f exit code, which varies across versions/HTTP-2 (a 404 can
    surface as exit 22 OR 56). Non-zero exit here therefore means a real transport failure.
    404 -> NotFound; other 4xx/5xx -> URLError.
    """
    marker = "\n__HTTP_STATUS__:"
    proc = subprocess.run(
        ["curl", "-sS", "--max-time", str(HTTP_TIMEOUT_S),
         "-H", "Accept: application/json", "-w", marker + "%{http_code}", url],
        capture_output=True, text=True)
    if proc.returncode != 0:
        raise urllib.error.URLError(f"curl transport error ({proc.returncode}): {proc.stderr.strip()}")

    body, _, status_str = proc.stdout.rpartition(marker)
    try:
        status = int(status_str.strip())
    except ValueError:
        status = 0
    if status == 404:
        raise NotFound(url)
    if status >= 400:
        raise urllib.error.URLError(f"HTTP {status} from {url}")
    return json.loads(body)


def fetch_market(base_url, market_ticker):
    """Return the raw market dict for a ticker, or None if not found."""
    url = f"{base_url}/markets/{market_ticker}"
    try:
        data = _http_get_json(url)
    except NotFound:
        return None
    return data.get("market")


def fetch_event_markets(base_url, event_ticker):
    """Return the list of market dicts nested under an event, or [] if none."""
    url = f"{base_url}/events/{event_ticker}?with_nested_markets=true"
    try:
        data = _http_get_json(url)
    except NotFound:
        return []
    event = data.get("event") or {}
    return event.get("markets") or []


def resolve_market(base_url, kalshi, cache):
    """
    Resolve a Kalshi market dict from an estimate's `kalshi` block.

    Prefers an explicit market_ticker (authoritative). Falls back to fetching the event and matching
    a single nested market only when the event has exactly one market or a ticker suffix is implied.
    Returns (market_dict, note) — market_dict is None if it couldn't be resolved unambiguously.
    """
    market_ticker = (kalshi or {}).get("market_ticker")
    event_ticker = (kalshi or {}).get("event_ticker")

    if market_ticker:
        if market_ticker in cache:
            return cache[market_ticker], None
        m = fetch_market(base_url, market_ticker)
        cache[market_ticker] = m
        if m is None:
            return None, f"market_ticker '{market_ticker}' not found on Kalshi (may have closed)"
        return m, None

    if event_ticker:
        markets = fetch_event_markets(base_url, event_ticker)
        if not markets:
            return None, f"event_ticker '{event_ticker}' not found or has no markets"
        if len(markets) == 1:
            return markets[0], "resolved via event_ticker (single market)"
        return None, (f"event '{event_ticker}' has {len(markets)} markets — add an explicit "
                      f"market_ticker to disambiguate which side is being estimated")

    return None, "no kalshi.market_ticker or kalshi.event_ticker provided"


# ---------------------------------------------------------------------------
# Math
# ---------------------------------------------------------------------------

def _to_float(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def american_odds(prob):
    """Fair American odds for a probability (for display)."""
    if prob is None or prob <= 0 or prob >= 1:
        return "n/a"
    if prob >= 0.5:
        return f"-{round(prob / (1 - prob) * 100)}"
    return f"+{round((1 - prob) / prob * 100)}"


def evaluate(estimate, market):
    """
    Compare one analyst estimate against one live market.

    Returns a dict of computed fields (or {'error': ...}). EV is per $1 contract:
    you pay `ask` now, receive $1 if the outcome resolves true, so
        EV = model_prob * (1 - ask) - (1 - model_prob) * ask = model_prob - ask.
    Edge-vs-mid uses the bid/ask midpoint as the market's "fair" implied probability.
    """
    outcome = (estimate.get("outcome") or "yes").lower()
    model_prob = _to_float(estimate.get("probability"))
    if model_prob is None or not (0.0 <= model_prob <= 1.0):
        return {"error": f"estimate.probability must be a number in [0,1], got {estimate.get('probability')!r}"}

    if outcome == "yes":
        ask = _to_float(market.get("yes_ask_dollars"))
        bid = _to_float(market.get("yes_bid_dollars"))
    elif outcome == "no":
        ask = _to_float(market.get("no_ask_dollars"))
        bid = _to_float(market.get("no_bid_dollars"))
    else:
        return {"error": f"estimate.outcome must be 'yes' or 'no', got {outcome!r}"}

    if ask is None:
        return {"error": f"market has no {outcome}_ask price (untradeable right now)"}

    # A resting ask of $0.00/$1.00 means no real offer — treat as unpriced rather than a 0%/100% edge.
    if ask <= 0.0 or ask >= 1.0:
        return {"error": f"{outcome}_ask is {ask:.2f} (no real resting offer) — not tradeable"}

    market_mid = None
    if bid is not None and 0.0 < bid < 1.0:
        market_mid = (bid + ask) / 2.0

    ev_per_contract = model_prob - ask                      # actionable: buy at the ask
    edge_vs_mid = (model_prob - market_mid) if market_mid is not None else None  # fairness signal

    return {
        "outcome": outcome,
        "model_prob": model_prob,
        "ask": ask,
        "bid": bid,
        "market_mid": market_mid,
        "ev_per_contract": ev_per_contract,
        "edge_vs_mid": edge_vs_mid,
        "market_status": market.get("status"),
        "market_ticker": market.get("ticker"),
    }


# ---------------------------------------------------------------------------
# Staleness / sanity flags
# ---------------------------------------------------------------------------

def staleness_flags(estimate_file, ev):
    flags = []
    status = ev.get("market_status")
    if status and status != "active":
        flags.append(f"market status={status} (not actively trading)")

    event_date = estimate_file.get("event_date")
    if event_date:
        try:
            ed = dt.date.fromisoformat(event_date)
            today = dt.date.today()
            if ed < today:
                flags.append(f"event_date {event_date} is in the past")
        except ValueError:
            flags.append(f"unparseable event_date {event_date!r}")

    fetched_at = estimate_file.get("fetched_at")
    if fetched_at:
        try:
            fa = dt.datetime.fromisoformat(fetched_at.replace("Z", "+00:00"))
            age_h = (dt.datetime.now(dt.timezone.utc) - fa).total_seconds() / 3600.0
            if age_h > 24:
                flags.append(f"analyst data is {age_h:.0f}h old")
        except ValueError:
            flags.append(f"unparseable fetched_at {fetched_at!r}")
    else:
        flags.append("no fetched_at timestamp in estimate")

    return flags


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------

def load_estimates(data_dir):
    """Load every *.json directly under data_dir (skips the reports/ subdir). Yields (path, obj, err)."""
    pattern = os.path.join(data_dir, "*.json")
    for path in sorted(glob.glob(pattern)):
        try:
            with open(path, "r", encoding="utf-8") as f:
                yield path, json.load(f), None
        except (json.JSONDecodeError, OSError) as e:
            yield path, None, str(e)


def process(data_dir, base_url, min_ev):
    rows = []
    skipped = []
    cache = {}

    any_files = False
    for path, obj, err in load_estimates(data_dir):
        any_files = True
        name = os.path.basename(path)
        if err:
            skipped.append((name, f"could not read/parse: {err}"))
            continue
        if not isinstance(obj, dict) or "estimate" not in obj or "kalshi" not in obj:
            skipped.append((name, "missing required 'estimate' and/or 'kalshi' fields"))
            continue

        try:
            market, note = resolve_market(base_url, obj.get("kalshi"), cache)
        except urllib.error.URLError as e:
            skipped.append((name, f"network error fetching Kalshi price: {e}"))
            continue

        if market is None:
            skipped.append((name, note or "could not resolve Kalshi market"))
            continue

        ev = evaluate(obj.get("estimate") or {}, market)
        if "error" in ev:
            skipped.append((name, ev["error"]))
            continue

        ev["file"] = name
        ev["outcome_label"] = (obj.get("kalshi") or {}).get("outcome_label") or ev["outcome"]
        ev["event_description"] = obj.get("event_description") or ""
        ev["sport"] = obj.get("sport") or ""
        ev["confidence"] = obj.get("confidence") or ""
        ev["resolve_note"] = note
        ev["flags"] = staleness_flags(obj, ev)
        rows.append(ev)

    rows.sort(key=lambda r: r["ev_per_contract"], reverse=True)
    return rows, skipped, any_files


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

def _pct(x):
    return "n/a" if x is None else f"{x * 100:.0f}%"


def _signed_cents(x):
    return "n/a" if x is None else f"{'+' if x >= 0 else '-'}{abs(x) * 100:.1f}c"


def render(rows, skipped, min_ev, data_dir):
    lines = []
    lines.append("# Kalshi mispricing report")
    lines.append("")
    lines.append(f"Generated {dt.datetime.now().astimezone().isoformat(timespec='seconds')} · "
                 f"source `{data_dir}` · edge threshold ≥ {_signed_cents(min_ev)}/contract")
    lines.append("")
    lines.append("EV/contract = model probability − price you'd pay (the ask). A positive number is "
                 "the expected profit per $1 contract if the analyst's probability is right. "
                 "Edge-vs-mid compares the model probability to the bid/ask midpoint (a fairness "
                 "signal, ignoring the spread you actually cross).")
    lines.append("")

    actionable = [r for r in rows if r["ev_per_contract"] >= min_ev]
    watch = [r for r in rows if 0 <= r["ev_per_contract"] < min_ev]
    negative = [r for r in rows if r["ev_per_contract"] < 0]

    def table(title, items):
        if not items:
            return
        lines.append(f"## {title}")
        lines.append("")
        lines.append("| Outcome | Sport | Model | Ask | Mkt mid | Edge vs mid | EV/contract | Conf | Ticker | Flags |")
        lines.append("|---|---|---|---|---|---|---|---|---|---|")
        for r in items:
            flags = "; ".join(r["flags"]) if r["flags"] else ""
            lines.append(
                f"| {r['outcome_label']} ({r['outcome']}) | {r['sport']} | {_pct(r['model_prob'])} "
                f"| {r['ask']*100:.0f}c | {_pct(r['market_mid'])} | {_signed_cents(r['edge_vs_mid'])} "
                f"| **{_signed_cents(r['ev_per_contract'])}** | {r['confidence']} "
                f"| `{r['market_ticker']}` | {flags} |")
        lines.append("")

    table(f"✅ Actionable (+EV ≥ {_signed_cents(min_ev)})", actionable)
    table("👀 Slight edge (below threshold)", watch)
    table("❌ Model agrees with / trails the market (no edge)", negative)

    if skipped:
        lines.append("## ⚠️ Skipped estimates")
        lines.append("")
        for name, reason in skipped:
            lines.append(f"- `{name}` — {reason}")
        lines.append("")

    if not rows and not skipped:
        lines.append("_No estimate files found. Run the `sports-stats-analyst` subagent first so it "
                     "writes probability estimates into this directory._")
        lines.append("")

    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Compare analyst win-probability estimates against live "
                                             "Kalshi prices to surface mispriced markets (advisory only).")
    ap.add_argument("--data-dir", default=DEFAULT_DATA_DIR,
                    help=f"Directory of analyst estimate JSON files (default: {DEFAULT_DATA_DIR})")
    ap.add_argument("--base-url", default=os.environ.get("KALSHI_BASE_URL", DEFAULT_BASE_URL),
                    help="Kalshi public API base URL")
    ap.add_argument("--min-ev", type=float, default=DEFAULT_MIN_EV,
                    help=f"Min EV/contract (in dollars) to call a market actionable (default: {DEFAULT_MIN_EV})")
    ap.add_argument("--no-report", action="store_true", help="Print to stdout only; don't write a .md file")
    args = ap.parse_args(argv)

    if not os.path.isdir(args.data_dir):
        log(f"Data dir '{args.data_dir}' does not exist. Nothing to do.")
        return 1

    rows, skipped, any_files = process(args.data_dir, args.base_url, args.min_ev)
    report = render(rows, skipped, args.min_ev, args.data_dir)
    print(report)

    if not args.no_report and any_files:
        reports_dir = os.path.join(args.data_dir, "reports")
        os.makedirs(reports_dir, exist_ok=True)
        stamp = dt.datetime.now().strftime("%Y%m%dT%H%M%S")
        out_path = os.path.join(reports_dir, f"mispricing-{stamp}.md")
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(report + "\n")
        log(f"\nWrote report to {out_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
