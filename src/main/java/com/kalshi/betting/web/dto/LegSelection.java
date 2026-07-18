package com.kalshi.betting.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** One chosen leg for a combo: which market, which event, and which side ("YES"/"NO") to take. */
public record LegSelection(

        @NotBlank
        String eventTicker,

        @NotBlank
        String marketTicker,

        @NotBlank
        @Pattern(regexp = "(?i)yes|no", message = "side must be YES or NO")
        String side
) {
}
