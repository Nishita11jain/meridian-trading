package com.trading.api.extensions.notifications;

import java.util.Optional;

/**
 * How the notifications module reads the alert a notification is about. Defined here, by the module that
 * needs it, and implemented by the watchlists module that owns alerts -- so watchlists depends on
 * notifications (for delivery and this lookup) and notifications never imports anything from watchlists.
 * The two modules do not form a cycle, and neither reads the other's tables.
 */
public interface AlertLookup {

    /** The alert, if it exists and belongs to the account; empty otherwise. */
    Optional<AlertDetails> findAlert(long accountId, long alertId);
}
