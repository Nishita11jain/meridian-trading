package com.trading.api.extensions.preferences;

/**
 * The seam the notifications module resolves a customer's channel through, in-process rather than
 * over HTTP. This is the only part of the preferences module another module may depend on; nothing
 * else in the package is imported from outside it.
 *
 * <p>Published as an interface so notifications can be built and tested against it before the
 * implementation is final, and so resolution never becomes an HTTP route anyone holding a customer
 * token could call (which would be the access-control finding the brief warns about).
 */
public interface CustomerChannelResolver {

    /**
     * The channel and contact a notification for this account should go out on. Never null: when the
     * customer has stored no preference, the documented default is returned (IN_APP, no external
     * contact), so a notification is never lost for want of a preference row.
     */
    NotificationRoute resolveChannel(long accountId);

    /** A resolved delivery route: the channel, and the contact detail for it where the channel needs one. */
    record NotificationRoute(String channel, String contact) {
    }
}
