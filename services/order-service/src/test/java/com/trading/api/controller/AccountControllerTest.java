package com.trading.api.controller;

import com.trading.api.JwtTestTokens;
import com.trading.api.dto.AccountResponse;
import com.trading.api.dto.BalanceResponse;
import com.trading.api.dto.OrderHistoryEntry;
import com.trading.api.dto.PositionResponse;
import com.trading.api.exception.GlobalExceptionHandler;
import com.trading.api.service.AccountService;
import com.trading.api.service.OrderService;
import com.trading.domain.AccountStatus;
import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;
import com.trading.domain.exception.AccountNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock
    private AccountService accountService;

    @Mock
    private OrderService orderService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {

        AccountController controller =
                new AccountController(
                        accountService,
                        orderService
                );

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(
                        new GlobalExceptionHandler()
                )
                .build();
    }

    @Test
    void getAccountReturnsMappedResponse()
            throws Exception {

        Instant lastUpdated =
                Instant.parse(
                        "2026-09-08T12:30:00Z"
                );

        AccountResponse response =
                new AccountResponse(
                        1L,
                        "ACC-REF-1",
                        "Holder",
                        new BigDecimal("1000.00"),
                        AccountStatus.ACTIVE,
                        4,
                        lastUpdated
                );

        when(accountService.getAccount(1L, 1L))
                .thenReturn(response);

        mockMvc.perform(
                get(
                        "/api/v1/accounts/{id}",
                        1L
                ).principal(jwtForAccount(1L))
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(
                jsonPath("$.accountId")
                        .value("ACC-REF-1")
        )
        .andExpect(
                jsonPath("$.holderName")
                        .value("Holder")
        )
        .andExpect(
                jsonPath("$.cashBalance")
                        .value(1000.00)
        )
        .andExpect(
                jsonPath("$.status")
                        .value("ACTIVE")
        )
        .andExpect(
                jsonPath("$.version")
                        .value(4)
        );

        verify(accountService)
                .getAccount(1L, 1L);
    }

    @Test
    void getBalanceReturnsSuccessfully()
            throws Exception {

        BalanceResponse response =
                new BalanceResponse(
                        1L,
                        new BigDecimal("1000.00"),
                        "INR",
                        Instant.parse(
                                "2026-09-08T12:00:00Z"
                        )
                );

        when(accountService.getBalance(1L, 1L))
                .thenReturn(response);

        mockMvc.perform(
                get(
                        "/api/v1/accounts/{id}/balance",
                        1L
                ).principal(jwtForAccount(1L))
        )
        .andExpect(status().isOk())
        .andExpect(
                jsonPath("$.accountId")
                        .value(1)
        )
        .andExpect(
                jsonPath("$.cashBalance")
                        .value(1000.00)
        )
        .andExpect(
                jsonPath("$.currency")
                        .value("INR")
        );

        verify(accountService)
                .getBalance(1L, 1L);
    }

    @Test
    void getPositionsReturnsSuccessfully()
            throws Exception {

        List<PositionResponse> response =
                List.of(
                        new PositionResponse(
                                1L,
                                "INFY.NS",
                                10,
                                new BigDecimal("100.00")
                        )
                );

        when(accountService.getPositions(1L, 1L))
                .thenReturn(response);

        mockMvc.perform(
                get(
                        "/api/v1/accounts/{id}/positions",
                        1L
                ).principal(jwtForAccount(1L))
        )
        .andExpect(status().isOk())
        .andExpect(
                jsonPath("$[0].accountId")
                        .value(1)
        )
        .andExpect(
                jsonPath("$[0].symbol")
                        .value("INFY.NS")
        )
        .andExpect(
                jsonPath("$[0].quantity")
                        .value(10)
        )
        .andExpect(
                jsonPath("$[0].averageCost")
                        .value(100.00)
        );

        verify(accountService)
                .getPositions(1L, 1L);
    }

    @Test
    void getOrdersParsesFiltersAndDelegatesToService()
            throws Exception {

        UUID orderId = UUID.randomUUID();

        Instant from =
                Instant.parse(
                        "2026-09-01T00:00:00Z"
                );

        Instant to =
                Instant.parse(
                        "2026-09-08T00:00:00Z"
                );

        OrderHistoryEntry entry =
                new OrderHistoryEntry(
                        "ORD-" + orderId,
                        1L,
                        "INFY.NS",
                        OrderSide.BUY,
                        10,
                        new BigDecimal("100.00"),
                        null,
                        OrderStatus.NEW,
                        "idem-key-001",
                        Instant.parse(
                                "2026-09-08T10:00:00Z"
                        ),
                        null
                );

        when(
                orderService.getOrderHistory(
                        1L,
                        1L,
                        OrderStatus.NEW,
                        from,
                        to
                )
        ).thenReturn(List.of(entry));

        mockMvc.perform(
                get(
                        "/api/v1/accounts/{id}/orders",
                        1L
                )
                .principal(jwtForAccount(1L))
                .queryParam("status", "NEW")
                .queryParam(
                        "from",
                        from.toString()
                )
                .queryParam(
                        "to",
                        to.toString()
                )
        )
        .andExpect(status().isOk())
        .andExpect(
                jsonPath("$[0].orderId")
                        .value("ORD-" + orderId)
        )
        .andExpect(
                jsonPath("$[0].accountId")
                        .value(1)
        )
        .andExpect(
                jsonPath("$[0].symbol")
                        .value("INFY.NS")
        )
        .andExpect(
                jsonPath("$[0].side")
                        .value("BUY")
        )
        .andExpect(
                jsonPath("$[0].status")
                        .value("NEW")
        );

        verify(orderService)
                .getOrderHistory(
                        1L,
                        1L,
                        OrderStatus.NEW,
                        from,
                        to
                );
    }

    @Test
    void unknownAccountReturns404()
            throws Exception {

        // The token itself must belong to a REAL mapped user (alice ->
        // account 1) -- only the requested path account (999) is unknown.
        // AccountServiceImpl checks existence before ownership, so this
        // correctly reaches ACC-404 rather than ACC-403.
        when(accountService.getAccount(999L, 1L))
                .thenThrow(
                        new AccountNotFoundException(
                                
                                "account not found"
                        )
                );

        mockMvc.perform(
                get(
                        "/api/v1/accounts/{id}",
                        999L
                ).principal(jwtForAccount(1L))
        )
        .andExpect(status().isNotFound())
        .andExpect(
                jsonPath("$.errorCode")
                        .value("ACC-404")
        )
        .andExpect(
                jsonPath("$.message")
                        .value("account not found")
        );
    }

    /**
     * Builds the principal a verified token produces: the account the caller may trade is the
     * accountId claim, exactly as contracts/api-schemas/auth-api.yaml defines it.
     */
    private static JwtAuthenticationToken jwtForAccount(long accountId) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(JwtTestTokens.subjectFor(accountId))
                .claim("accountId", accountId)
                .claim("roles", List.of("CUSTOMER"))
                .build();
        return new JwtAuthenticationToken(jwt);
    }
}
