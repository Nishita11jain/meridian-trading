package com.trading.api.extensions.preferences;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * Customer preferences routes, under the /api/v1 prefix the Trade REST API already verifies tokens
 * for. The signature, expiry, algorithm and issuer are checked before any method here runs; what is
 * decided here is whether this caller may reach this account, by passing the token's accountId claim
 * to the service, which refuses a mismatch with ACC-403.
 */
@RestController
@RequestMapping("/api/v1/preferences")
public class PreferencesController {

    private final PreferencesService preferencesService;

    public PreferencesController(PreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<PreferenceResponse> getPreferences(@PathVariable Long accountId,
                                                             Principal principal) {
        return ResponseEntity.ok(preferencesService.getPreferences(accountId, tokenAccountId(principal)));
    }

    @PutMapping("/{accountId}/notifications")
    public ResponseEntity<PreferenceResponse> updateNotification(
            @PathVariable Long accountId,
            @Valid @RequestBody UpdateNotificationPreferenceRequest request,
            Principal principal) {
        return ResponseEntity.ok(
                preferencesService.updateNotification(accountId, tokenAccountId(principal), request));
    }

    @PutMapping("/{accountId}/display")
    public ResponseEntity<PreferenceResponse> updateDisplay(
            @PathVariable Long accountId,
            @Valid @RequestBody UpdateDisplayPreferenceRequest request,
            Principal principal) {
        return ResponseEntity.ok(
                preferencesService.updateDisplay(accountId, tokenAccountId(principal), request));
    }

    @PutMapping("/{accountId}/avatar")
    public ResponseEntity<PreferenceResponse> updateAvatar(
            @PathVariable Long accountId,
            @Valid @RequestBody UpdateAvatarPreferenceRequest request,
            Principal principal) {
        return ResponseEntity.ok(
                preferencesService.updateAvatar(accountId, tokenAccountId(principal), request));
    }

    // The account a token may act on is its accountId claim and nothing else -- never a path value, a
    // body field or a username. SecurityConfig has already refused any token without a positive
    // whole-number accountId, so a verified token always carries one. Same pattern as the Sprint 6
    // controllers, so the boundary reads identically across the service.
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
