package com.trading.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Team-owned test fixture -- mints real, correctly-signed tokens using the
 * same JWT_SECRET the application itself verifies with, so tests exercise
 * the real production verification path. Never deployed as a service.
 *
 * <p>The tokens have the claim set contracts/api-schemas/auth-api.yaml fixes: {@code sub}
 * a UUID, {@code accountId} an integer, {@code roles} ["CUSTOMER"], plus
 * {@code iss}, {@code iat} and {@code exp}. The account a token may trade is
 * its {@code accountId} claim and nothing else.
 */
public final class JwtTestTokens {

    private static final String ISSUER = "auth-service";

    private JwtTestTokens() {
    }

    public static String validTokenFor(long accountId, String secret) throws Exception {
        return build(subjectFor(accountId), accountId, Instant.now().plusSeconds(900), secret, ISSUER);
    }

    /** Correctly signed and unexpired, but issued by someone other than the configured issuer. */
    public static String tokenWithIssuer(long accountId, String issuer, String secret) throws Exception {
        return build(subjectFor(accountId), accountId, Instant.now().plusSeconds(900), secret, issuer);
    }

    public static String expiredTokenFor(long accountId, String secret) throws Exception {
        return build(subjectFor(accountId), accountId, Instant.now().minusSeconds(60), secret, ISSUER);
    }

    /** Correctly signed, unexpired and from the right issuer, but with no accountId claim at all. */
    public static String tokenWithoutAccountId(String secret) throws Exception {
        return build(UUID.randomUUID().toString(), null, Instant.now().plusSeconds(900), secret, ISSUER);
    }

    /** Correctly signed, but the accountId claim is whatever the test supplies (a string, zero, -1, 1.5 ...). */
    public static String tokenWithAccountIdClaim(Object accountIdClaim, String secret) throws Exception {
        return build(UUID.randomUUID().toString(), accountIdClaim, Instant.now().plusSeconds(900), secret, ISSUER);
    }

    /** The same subject every time for one account, so repeated tokens look like one customer. */
    public static String subjectFor(long accountId) {
        return UUID.nameUUIDFromBytes(("account-" + accountId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String build(String subject, Object accountId, Instant expiry, String secret, String issuer)
            throws Exception {
        JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("roles", List.of("CUSTOMER"))
                .issueTime(new Date())
                .expirationTime(Date.from(expiry));
        if (issuer != null) {
            claimsBuilder.issuer(issuer);
        }
        if (accountId != null) {
            claimsBuilder.claim("accountId", accountId);
        }
        JWTClaimsSet claims = claimsBuilder.build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        JWSSigner signer = new MACSigner(secret.getBytes());
        jwt.sign(signer);
        return jwt.serialize();
    }
}
