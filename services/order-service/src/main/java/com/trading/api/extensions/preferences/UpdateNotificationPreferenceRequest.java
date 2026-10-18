package com.trading.api.extensions.preferences;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sets the channel a customer is reached on, and the contact detail for it. The shape is validated
 * here; whether the contact detail is the right kind for the chosen channel (an email for EMAIL, a
 * phone for SMS) is a cross-field rule the service checks, because it cannot be expressed per field.
 */
public record UpdateNotificationPreferenceRequest(
        @NotBlank(message = "notificationChannel is required")
        String notificationChannel,

        @Email(message = "contactEmail must be a valid email address")
        @Size(max = 320, message = "contactEmail is too long")
        String contactEmail,

        @Size(max = 32, message = "contactPhone is too long")
        String contactPhone
) {
}
