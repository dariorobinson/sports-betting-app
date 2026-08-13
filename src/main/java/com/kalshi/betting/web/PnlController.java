package com.kalshi.betting.web;

import com.kalshi.betting.service.PnlService;
import com.kalshi.betting.web.dto.PnlReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Realized P&L / win-rate tracking, from Kalshi settlements — pure Java, no LLM (zero Anthropic
 * cost). {@code GET /api/pnl} returns today / week-to-date / month-to-date rollups plus a trailing
 * per-day breakdown. Protected the same as every other {@code /api/**} endpoint by
 * {@link com.kalshi.betting.config.ApiKeyFilter}.
 */
@RestController
@RequestMapping("/api/pnl")
public class PnlController {

    private final PnlService pnlService;

    public PnlController(PnlService pnlService) {
        this.pnlService = pnlService;
    }

    @GetMapping
    public PnlReport getPnl() {
        return pnlService.getReport();
    }
}
