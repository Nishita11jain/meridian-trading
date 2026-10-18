package com.trading.api.controller;

import com.trading.api.dto.OrderResponse;
import com.trading.api.service.OrderService;
import com.trading.domain.PlaceOrderRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody PlaceOrderRequest request,
                                                      Principal principal) {
        // The strategy engine signs in as "strategy-engine:<id>" (decision 0010). Customer tokens look the same
        // to every other check, so say in the log when an order came from a strategy rather than a person.
        if (principal instanceof JwtAuthenticationToken jwt
                && jwt.getToken().getSubject() != null
                && jwt.getToken().getSubject().startsWith("strategy-engine:")) {
            log.info("Order for account {} on {} placed by {}", request.accountId(), request.symbol(),
                    jwt.getToken().getSubject());
        }
        return ResponseEntity.ok(orderService.placeOrder(request, tokenAccountId(principal)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<OrderResponse> cancelOrder(@PathVariable UUID id, Principal principal) {
        return ResponseEntity.ok(orderService.cancelOrder(id, tokenAccountId(principal)));
    }

    private Long tokenAccountId(Principal principal) {
        if (!(principal instanceof JwtAuthenticationToken authentication)) {
            throw new IllegalStateException("Unsupported authenticated principal: " + principal);
        }
        // SecurityConfig refuses a token that has no positive whole-number accountId claim before
        // any controller runs, so a verified token always carries one. The account comes from the
        // claim and nowhere else: never from a username, a lookup table or the request.
        Object claim = authentication.getToken().getClaim("accountId");
        if (claim instanceof Number accountId) {
            return accountId.longValue();
        }
        throw new IllegalStateException("A verified token always carries a numeric accountId claim");
    }
}
