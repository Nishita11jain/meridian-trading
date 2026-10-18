package com.trading.api.extensions.preferences;

import java.time.Instant;

/**
 * A customer's preferences as returned to the UI. {@code updatedOn} is null when no preference has
 * been stored yet and the platform defaults are being returned, which the screen uses to show
 * "not set yet" rather than a stale timestamp.
 */
public record PreferenceResponse(
        Long accountId,
        Long defaultAccountId,
        String notificationChannel,
        String contactEmail,
        String contactPhone,
        String displayCurrency,
        String theme,
        String avatarKey,
        Instant updatedOn
) {
}
