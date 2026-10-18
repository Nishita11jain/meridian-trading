package com.trading.api.extensions.preferences;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Sets how amounts are formatted and which theme the UI uses. The currency must be a three-letter
 * code in shape here; that it is a currency the platform actually trades in is checked by the
 * service against the instrument catalogue, because there is no fixed list to validate against.
 */
public record UpdateDisplayPreferenceRequest(
        @NotBlank(message = "displayCurrency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "displayCurrency must be a three-letter code")
        String displayCurrency,

        @NotBlank(message = "theme is required")
        String theme
) {
}
