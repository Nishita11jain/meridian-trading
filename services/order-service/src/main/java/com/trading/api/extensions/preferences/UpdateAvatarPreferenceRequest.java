package com.trading.api.extensions.preferences;

import jakarta.validation.constraints.NotBlank;

/**
 * Chooses one of the predefined avatars. The key must be one the platform ships; that is checked by the
 * service against {@link PreferencesService#AVATAR_KEYS}, so an unknown key is refused as VAL-422.
 */
public record UpdateAvatarPreferenceRequest(
        @NotBlank(message = "avatarKey is required")
        String avatarKey
) {
}
