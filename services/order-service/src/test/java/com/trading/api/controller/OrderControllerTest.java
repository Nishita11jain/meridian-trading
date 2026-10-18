package com.trading.api.controller;

import com.trading.api.JwtTestTokens;
import com.trading.api.exception.OrderNotFoundException;
import com.trading.api.dto.OrderResponse;
import com.trading.api.exception.GlobalExceptionHandler;
import com.trading.api.service.OrderService;
import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;
import com.trading.domain.PlaceOrderRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    private static final LocalValidatorFactoryBean VALIDATOR = new LocalValidatorFactoryBean();

    @Mock
    private OrderService orderService;

    private MockMvc mockMvc;

    @BeforeAll
    static void createValidator() {
        VALIDATOR.afterPropertiesSet();
    }

    @AfterAll
    static void closeValidator() {
        VALIDATOR.close();
    }

    @BeforeEach
    void setUp() {
        OrderController controller = new OrderController(orderService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(VALIDATOR)
                .build();
    }

    @Test
    void placeOrderDelegatesAndReturnsResponseBody() throws Exception {
        PlaceOrderRequest request = new PlaceOrderRequest(1L, "INFY.NS", OrderSide.BUY, 10,
                new BigDecimal("100.00"), "idem-key-001");
        OrderResponse response = new OrderResponse("ORD-123", OrderStatus.NEW, "Order accepted",
                "INFY.NS", OrderSide.BUY, 10, new BigDecimal("100.00"));
        when(orderService.placeOrder(any(PlaceOrderRequest.class), anyLong())).thenReturn(response);

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                .principal(jwtForAccount(1L))
                        .content("""
        {
          "accountId": 1,
          "symbol": "INFY.NS",
          "side": "BUY",
          "quantity": 10,
          "price": 100.00,
          "idempotencyKey": "idem-key-001"
        }
        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ORD-123"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.symbol").value("INFY.NS"))
                .andExpect(jsonPath("$.side").value("BUY"))
                .andExpect(jsonPath("$.quantity").value(10))
                .andExpect(jsonPath("$.price").value(100.00));

            verify(orderService).placeOrder(request, 1L);
    }

    @Test
    void invalidOrderRequestReturnsValidationEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {
          "accountId": 0,
          "symbol": "",
          "side": "BUY",
          "quantity": 0,
          "price": 0.00,
          "idempotencyKey": "short"
        }
        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"))
                .andExpect(jsonPath("$.message").value("Invalid input"));

        verifyNoInteractions(orderService);
    }

    @Test
    void malformedOrderIdReturnsValidationEnvelope() throws Exception {
        mockMvc.perform(delete("/api/v1/orders/not-a-uuid"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"))
                .andExpect(jsonPath("$.message").value("Invalid input"));

        verifyNoInteractions(orderService);
    }

    @Test
    void cancellingAnUnknownOrderAnswers404WithTheContractBody() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orderService.cancelOrder(orderId, 1L)).thenThrow(new OrderNotFoundException("Order not found"));

        mockMvc.perform(delete("/api/v1/orders/{id}", orderId).principal(jwtForAccount(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORD-409"))
                .andExpect(jsonPath("$.message").value("Order not found"));
    }

    @Test
    void cancelOrderDelegatesAndReturnsResponseBody() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderResponse response = new OrderResponse("ORD-456", OrderStatus.CANCELLED, "Order cancelled",
                "INFY.NS", OrderSide.BUY, 10, new BigDecimal("100.00"));
        when(orderService.cancelOrder(orderId, 1L)).thenReturn(response);

        mockMvc.perform(delete("/api/v1/orders/{id}", orderId).principal(jwtForAccount(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ORD-456"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.message").value("Order cancelled"));

        verify(orderService).cancelOrder(orderId, 1L);
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
