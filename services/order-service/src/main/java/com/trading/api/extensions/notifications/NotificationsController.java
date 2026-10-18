package com.trading.api.extensions.notifications;

import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

/**
 * The customer inbox routes. Read the account's notifications (with the unread count), and mark them
 * all read. Both compare the account in the path against the token's accountId claim and refuse a
 * mismatch with ACC-403, so one customer cannot read another's inbox.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationsController {

    private final NotificationsService notificationsService;

    public NotificationsController(NotificationsService notificationsService) {
        this.notificationsService = notificationsService;
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<NotificationListResponse> list(@PathVariable Long accountId, Principal principal) {
        return ResponseEntity.ok(notificationsService.list(accountId, tokenAccountId(principal)));
    }

    @PostMapping("/{accountId}/read")
    public ResponseEntity<NotificationListResponse> markAllRead(@PathVariable Long accountId, Principal principal) {
        return ResponseEntity.ok(notificationsService.markAllRead(accountId, tokenAccountId(principal)));
    }

    @GetMapping("/{accountId}/{id}")
    public ResponseEntity<NotificationDetailResponse> detail(@PathVariable Long accountId, @PathVariable UUID id,
                                                             Principal principal) {
        return ResponseEntity.ok(notificationsService.detail(accountId, tokenAccountId(principal), id));
    }

    @PostMapping("/{accountId}/{id}/read")
    public ResponseEntity<NotificationListResponse> markRead(@PathVariable Long accountId, @PathVariable UUID id,
                                                             Principal principal) {
        return ResponseEntity.ok(notificationsService.markRead(accountId, tokenAccountId(principal), id));
    }

    @PostMapping("/{accountId}/{id}/unread")
    public ResponseEntity<NotificationListResponse> markUnread(@PathVariable Long accountId, @PathVariable UUID id,
                                                               Principal principal) {
        return ResponseEntity.ok(notificationsService.markUnread(accountId, tokenAccountId(principal), id));
    }

    private Long tokenAccountId(Principal principal) {
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
