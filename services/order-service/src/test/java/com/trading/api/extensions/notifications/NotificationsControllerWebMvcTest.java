package com.trading.api.extensions.notifications;

import com.trading.api.JwtTestTokens;
import com.trading.api.config.SecurityConfig;
import com.trading.api.exception.GlobalExceptionHandler;
import com.trading.domain.exception.AccountNotActiveException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = NotificationsController.class,
        properties = "app.security.jwt-secret=01234567890123456789012345678901"
)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class NotificationsControllerWebMvcTest {

    private static final String JWT_SECRET = "01234567890123456789012345678901";
    private static final long ACCOUNT = 4L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationsService notificationsService;

    private String bearer(long accountId) throws Exception {
        return "Bearer " + JwtTestTokens.validTokenFor(accountId, JWT_SECRET);
    }

    private static NotificationListResponse sample() {
        return new NotificationListResponse(List.of(new NotificationResponse(
                UUID.randomUUID(), ACCOUNT, "ORDER_FILLED", "EMAIL", "Order filled: TCS.NS",
                "Your BUY order for 10 TCS.NS was filled at 3425.00.", "SENT", null, null, null, null, "TCS.NS")), 1);
    }

    @Test
    void missingTokenIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/4"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
    }

    @Test
    void ownInboxIsReturnedWithUnreadCount() throws Exception {
        when(notificationsService.list(ACCOUNT, ACCOUNT)).thenReturn(sample());

        mockMvc.perform(get("/api/v1/notifications/4").header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.notifications[0].subject").value("Order filled: TCS.NS"))
                .andExpect(jsonPath("$.notifications[0].channel").value("EMAIL"));
    }

    @Test
    void anotherAccountsInboxIsRefusedAcc403() throws Exception {
        when(notificationsService.list(eq(5L), eq(ACCOUNT)))
                .thenThrow(new AccountNotActiveException("account is not active"));

        mockMvc.perform(get("/api/v1/notifications/5").header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));
    }

    @Test
    void markAllReadReturnsTheClearedInbox() throws Exception {
        when(notificationsService.markAllRead(ACCOUNT, ACCOUNT))
                .thenReturn(new NotificationListResponse(List.of(), 0));

        mockMvc.perform(post("/api/v1/notifications/4/read").header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void markingAnotherAccountReadIsRefused() throws Exception {
        when(notificationsService.markAllRead(eq(5L), eq(ACCOUNT)))
                .thenThrow(new AccountNotActiveException("account is not active"));

        mockMvc.perform(post("/api/v1/notifications/5/read").header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));
        verify(notificationsService, never()).list(any(), any());
    }

    private static final UUID ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    void openingANotificationReturnsItWithTheOrderItIsAbout() throws Exception {
        NotificationResponse notification = new NotificationResponse(ID, ACCOUNT, "ORDER_REJECTED", "IN_APP",
                "Order rejected: TCS.NS", "body", "SENT", null, null, "ORD-33333333-3333-3333-3333-333333333333",
                null, "TCS.NS");
        OrderDetails order = new OrderDetails("ORD-33333333-3333-3333-3333-333333333333", "TCS.NS", "BUY", 1000,
                new java.math.BigDecimal("56678.00"), null, "REJECTED", "INSUFFICIENT_FUNDS", null);
        when(notificationsService.detail(ACCOUNT, ACCOUNT, ID))
                .thenReturn(new NotificationDetailResponse(notification, order, null));

        mockMvc.perform(get("/api/v1/notifications/4/" + ID).header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notification.symbol").value("TCS.NS"))
                .andExpect(jsonPath("$.order.rejectionReason").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.alert").doesNotExist());
    }

    @Test
    void anUnknownNotificationIsNtf404() throws Exception {
        when(notificationsService.detail(ACCOUNT, ACCOUNT, ID))
                .thenThrow(new NotificationNotFoundException("notification not found"));

        mockMvc.perform(get("/api/v1/notifications/4/" + ID).header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NTF-404"));
    }

    @Test
    void markingOneReadAndUnreadReturnsTheInbox() throws Exception {
        when(notificationsService.markRead(ACCOUNT, ACCOUNT, ID)).thenReturn(new NotificationListResponse(List.of(), 2));
        when(notificationsService.markUnread(ACCOUNT, ACCOUNT, ID)).thenReturn(new NotificationListResponse(List.of(), 3));

        mockMvc.perform(post("/api/v1/notifications/4/" + ID + "/read").header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2));
        mockMvc.perform(post("/api/v1/notifications/4/" + ID + "/unread").header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(3));
        verify(notificationsService, never()).markAllRead(any(), any());
    }

    @Test
    void openingRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/4/" + ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/notifications/4/" + ID + "/unread"))
                .andExpect(status().isUnauthorized());
    }
}
