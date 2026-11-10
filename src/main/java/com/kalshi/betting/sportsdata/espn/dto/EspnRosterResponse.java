package com.kalshi.betting.sportsdata.espn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A team's full roster. ESPN's shape here is inconsistent across sports/teams — confirmed live:
 * NBA returns a flat list of athlete objects directly under {@code athletes}, while NFL and (at
 * least some) college football teams return {@code athletes} as a list of position GROUPS, each
 * wrapping its own nested {@code items} array of athlete objects. {@link Entry} models both shapes
 * at once (Jackson just leaves whichever fields aren't present as null) so {@link #allAthletes()}
 * can flatten either into one real athlete list regardless of which shape this particular call
 * returned — getting this wrong previously meant a roster fetch for a grouped-shape team silently
 * resolved zero real athletes (every field null) instead of erroring, so player-prop lookups for
 * SOME teams would never find a match no matter how exact the player's name was.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnRosterResponse(List<Entry> athletes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
            // Present when `athletes` is a flat list of real athlete objects.
            String id, String firstName, String lastName, String displayName, String fullName,
            String shortName, List<EspnInjury> injuries,
            // Present when `athletes` is grouped by position instead — only `items` is used.
            String position, List<EspnAthlete> items
    ) {
        /** This entry as however many real athletes it actually represents — one, if this is the
         *  flat shape and {@code id} is populated; whichever are in {@code items}, if this is the
         *  grouped shape; otherwise none. */
        List<EspnAthlete> resolve() {
            if (items != null && !items.isEmpty()) {
                return items;
            }
            if (id != null) {
                return List.of(new EspnAthlete(id, firstName, lastName, displayName, fullName, shortName, injuries));
            }
            return List.of();
        }
    }

    /** Every real athlete on this roster, regardless of which raw shape ESPN returned — see the
     *  class javadoc. */
    public List<EspnAthlete> allAthletes() {
        return athletes == null ? List.of()
                : athletes.stream().flatMap(e -> e.resolve().stream()).toList();
    }
}
