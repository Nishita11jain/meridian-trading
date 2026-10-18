package com.trading.api.controller;

import com.trading.api.dto.AccountResponse;
import com.trading.api.dto.BalanceResponse;
import com.trading.api.dto.OrderHistoryEntry;
import com.trading.api.dto.PositionResponse;
import com.trading.api.service.AccountService;
import com.trading.api.service.OrderService;
import com.trading.domain.OrderStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;
    private final OrderService orderService;

    public AccountController(AccountService accountService, OrderService orderService) {
        this.accountService = accountService;
        this.orderService = orderService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable Long id, Principal principal) {
        return ResponseEntity.ok(accountService.getAccount(id, tokenAccountId(principal)));
    }

    @GetMapping("/{id}/balance")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable Long id, Principal principal) {
        return ResponseEntity.ok(accountService.getBalance(id, tokenAccountId(principal)));
    }

    @GetMapping("/{id}/positions")
    public ResponseEntity<List<PositionResponse>> getPositions(@PathVariable Long id, Principal principal) {
        return ResponseEntity.ok(accountService.getPositions(id, tokenAccountId(principal)));
    }

    @GetMapping("/{id}/orders")
    public ResponseEntity<List<OrderHistoryEntry>> getOrders(
            @PathVariable Long id,
            Principal principal,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(orderService.getOrderHistory(id, tokenAccountId(principal), status, from, to));
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
