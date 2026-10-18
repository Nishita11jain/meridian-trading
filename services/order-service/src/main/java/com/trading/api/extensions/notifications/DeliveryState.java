package com.trading.api.extensions.notifications;

/**
 * Represents the delivery state of a notification. This indicates whether the notification has been
 * delivered to the customer or if it is pending, queued, or failed.
 */
public enum DeliveryState {
    /**
     * Notification created and queued for delivery on a channel the platform does not actually send
     * on yet (EMAIL). No message has left the platform; this records the intent honestly.
     */
    QUEUED("QUEUED"),

    /**
     * Notification delivered to the customer's in-app inbox.
     */
    SENT("SENT"),

    /**
     * Notification delivery failed. It is not in the inbox and not queued for retry.
     */
    FAILED("FAILED");

    private final String value;

    DeliveryState(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static DeliveryState fromValue(String value) {
        for (DeliveryState state : DeliveryState.values()) {
            if (state.value.equalsIgnoreCase(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown delivery state: " + value);
    }
}