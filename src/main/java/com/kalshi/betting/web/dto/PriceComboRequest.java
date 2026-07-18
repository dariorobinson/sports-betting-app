package com.kalshi.betting.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record PriceComboRequest(

        @NotEmpty
        @Valid
        List<LegSelection> legs
) {
}
