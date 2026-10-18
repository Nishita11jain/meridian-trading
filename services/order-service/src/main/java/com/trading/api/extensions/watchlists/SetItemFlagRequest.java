package com.trading.api.extensions.watchlists;

import jakarta.validation.constraints.Pattern;

/** Put a colour flag on an instrument in a watchlist; null or blank removes it. */
public record SetItemFlagRequest(
        @Pattern(regexp = "(?i)^(RED|AMBER|GREEN)?$", message = "flag must be RED, AMBER or GREEN")
        String flag) {

    /** The colours a flag can have. */
    public static final java.util.Set<String> COLOURS = java.util.Set.of("RED", "AMBER", "GREEN");
}
