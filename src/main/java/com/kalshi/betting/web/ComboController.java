package com.kalshi.betting.web;

import com.kalshi.betting.service.ComboService;
import com.kalshi.betting.web.dto.ComboCollectionSummary;
import com.kalshi.betting.web.dto.ComboLegsResponse;
import com.kalshi.betting.web.dto.ComboPriceResponse;
import com.kalshi.betting.web.dto.PriceComboRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/combos")
public class ComboController {

    private final ComboService comboService;

    public ComboController(ComboService comboService) {
        this.comboService = comboService;
    }

    @GetMapping
    public List<ComboCollectionSummary> listCombos() {
        return comboService.listSportsCombos();
    }

    @GetMapping("/{collectionTicker}/legs")
    public ComboLegsResponse getLegs(@PathVariable String collectionTicker,
                                      @RequestParam(required = false) String seriesTicker) {
        return comboService.getComboLegs(collectionTicker, seriesTicker);
    }

    @PostMapping("/{collectionTicker}/price")
    public ComboPriceResponse priceCombo(@PathVariable String collectionTicker,
                                          @Valid @RequestBody PriceComboRequest request) {
        return comboService.priceCombo(collectionTicker, request.legs());
    }
}
