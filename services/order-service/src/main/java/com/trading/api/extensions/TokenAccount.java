package com.trading.api.extensions;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.security.Principal;

/**
 * The one way the extension controllers read the account a request may act on: the verified token's
 * accountId claim, never a path value, a body field or a username. SecurityConfig has already refused
 * any token without a positive whole-number accountId, so a verified token always carries one.
 */
public final class TokenAccount {

    private TokenAccount() {
    }

    public static long of(Principal principal) {
        if (!(principal instanceof JwtAuthenticationToken authentication)) {
            throw new IllegalStateException("Unsupported authenticated principal: " + principal);
        }
        Object claim = authentication.getToken().getClaim("accountId");
        if (claim instanceof Number accountId) {
            return accountId.longValue();
        }
        throw new IllegalStateException("A verified token always carries a numeric accountId claim");
    }
}
