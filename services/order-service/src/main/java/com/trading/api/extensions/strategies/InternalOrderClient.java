package com.trading.api.extensions.strategies;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.trading.api.dto.OrderResponse;
import com.trading.domain.OrderSide;
import com.trading.domain.PlaceOrderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * The only way a strategy places an order: mints a single-purpose internal token and calls this
 * service's own {@code POST /api/v1/orders} over real HTTP, so the route's own validation,
 * authorization and idempotency check are never bypassed. See decision-log/0010 for why this is a
 * self-HTTP call rather than a direct method call into {@code OrderService}. The token is minted
 * fresh for each call, lives 30 seconds, and is never logged, persisted or returned to any caller.
 */
@Component
public class InternalOrderClient {

    private static final Logger log = LoggerFactory.getLogger(InternalOrderClient.class);
    private static final long TOKEN_TTL_SECONDS = 30;

    private final RestClient restClient;
    private final byte[] jwtSecret;
    private final String jwtIssuer;

    public InternalOrderClient(
            @Value("${app.internal-base-url}") String baseUrl,
            @Value("${app.security.jwt-secret}") String jwtSecret,
            @Value("${app.security.jwt-issuer:auth-service}") String jwtIssuer) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(5_000);
        requests.setReadTimeout(10_000);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build();
        this.jwtSecret = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.jwtIssuer = jwtIssuer;
    }

    /** Places one order on the strategy's own account. Never throws for a rejected order -- only for a transport failure. */
    public OrderOutcome placeOrder(Long strategyId, Long accountId, String symbol, String side,
                                    int quantity, BigDecimal price, String idempotencyKey) {
        String token = mintToken(strategyId, accountId);
        PlaceOrderRequest request = new PlaceOrderRequest(
                accountId, symbol, OrderSide.valueOf(side), quantity, price, idempotencyKey);
        try {
            OrderResponse response = restClient.post()
                    .uri("/api/v1/orders")
                    .header("Authorization", "Bearer " + token)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(OrderResponse.class);
            if (response == null) {
                return OrderOutcome.transportFailure("The order route returned no body.");
            }
            // A business rejection (insufficient funds/holdings) comes back as a normal 200 with
            // status REJECTED, not an exception -- so "accepted" has to read the status, not just
            // whether the call threw.
            boolean accepted = response.status() == com.trading.domain.OrderStatus.NEW
                    || response.status() == com.trading.domain.OrderStatus.FILLED;
            return new OrderOutcome(accepted, response.status().name(), response.message(), response.orderId());
        } catch (RestClientResponseException rejected) {
            log.info("Strategy {} order placement rejected: {} {}", strategyId,
                    rejected.getStatusCode(), rejected.getResponseBodyAsString());
            return new OrderOutcome(false, "REJECTED", rejected.getResponseBodyAsString(), null);
        } catch (Exception transportFailure) {
            log.warn("Strategy {} could not reach the order route: {}", strategyId, transportFailure.getMessage());
            return OrderOutcome.transportFailure(transportFailure.getMessage());
        }
    }

    private String mintToken(Long strategyId, Long accountId) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("strategy-engine:" + strategyId)
                    .claim("accountId", accountId)
                    .claim("roles", java.util.List.of("CUSTOMER"))
                    .issuer(jwtIssuer)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(TOKEN_TTL_SECONDS)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(jwtSecret));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Could not mint an internal order-placement token", e);
        }
    }

    /** {@code accepted} is true only when the order route accepted the request at all (NEW/FILLED), never on a rejection. */
    public record OrderOutcome(boolean accepted, String status, String message, String orderId) {
        static OrderOutcome transportFailure(String message) {
            return new OrderOutcome(false, "UNAVAILABLE", message, null);
        }
    }
}
