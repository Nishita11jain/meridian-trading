package com.trading.api.extensions.preferences;

import com.trading.api.mapper.InstrumentMapper;
import com.trading.domain.exception.AccountNotActiveException;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Owns a customer's personalisation: the channel notifications reach them on and its contact detail,
 * the currency amounts are formatted in, and the UI theme. It is also the platform's single
 * {@link CustomerChannelResolver}: notifications resolves a channel through this class, in-process,
 * so there is one place that decides how to reach a customer.
 *
 * <p>Access control: every account-addressed method compares the account in the path against the
 * account the verified token carries, and refuses a mismatch with ACC-403 -- the same answer the
 * account routes give, so one customer cannot read or change another's preferences.
 */
@Service
public class PreferencesService implements CustomerChannelResolver {

    /** Defaults applied when a customer has stored nothing yet. Also what the resolver returns then. */
    static final String DEFAULT_CHANNEL = "IN_APP";
    static final String DEFAULT_CURRENCY = "INR";
    static final String DEFAULT_THEME = "SYSTEM";
    static final String DEFAULT_AVATAR = "avatar_01";

    private static final Set<String> CHANNELS = Set.of("IN_APP", "EMAIL", "SMS", "PUSH");
    private static final Set<String> THEMES = Set.of("LIGHT", "DARK", "SYSTEM");

    /** The predefined avatars. The images ship with the frontend; these keys are all the server stores. */
    public static final Set<String> AVATAR_KEYS = Set.of(
            "avatar_01", "avatar_02", "avatar_03", "avatar_04",
            "avatar_05", "avatar_06", "avatar_07", "avatar_08");

    private final PreferencesMapper preferencesMapper;
    private final InstrumentMapper instrumentMapper;

    public PreferencesService(PreferencesMapper preferencesMapper, InstrumentMapper instrumentMapper) {
        this.preferencesMapper = preferencesMapper;
        this.instrumentMapper = instrumentMapper;
    }

    public PreferenceResponse getPreferences(Long accountId, Long tokenAccountId) {
        requireOwnership(accountId, tokenAccountId);
        return toResponse(accountId, preferencesMapper.findByAccountId(accountId));
    }

    public PreferenceResponse updateNotification(Long accountId, Long tokenAccountId,
                                                 UpdateNotificationPreferenceRequest request) {
        requireOwnership(accountId, tokenAccountId);

        String channel = normalise(request.notificationChannel());
        if (!CHANNELS.contains(channel)) {
            throw new InvalidPreferenceException("notificationChannel must be one of " + CHANNELS);
        }
        // The contact has to match the channel, or a message has nowhere to go. IN_APP and PUSH are
        // delivered inside the platform, so they need no external contact.
        if ("EMAIL".equals(channel) && isBlank(request.contactEmail())) {
            throw new InvalidPreferenceException("contactEmail is required for the EMAIL channel");
        }
        if ("SMS".equals(channel) && isBlank(request.contactPhone())) {
            throw new InvalidPreferenceException("contactPhone is required for the SMS channel");
        }

        preferencesMapper.upsertNotification(accountId, accountId, channel,
                trimToNull(request.contactEmail()), trimToNull(request.contactPhone()));
        return toResponse(accountId, preferencesMapper.findByAccountId(accountId));
    }

    public PreferenceResponse updateDisplay(Long accountId, Long tokenAccountId,
                                            UpdateDisplayPreferenceRequest request) {
        requireOwnership(accountId, tokenAccountId);

        String currency = normalise(request.displayCurrency());
        if (!instrumentMapper.findDistinctCurrencies().contains(currency)) {
            throw new InvalidPreferenceException("displayCurrency must be a currency the platform trades in");
        }
        String theme = normalise(request.theme());
        if (!THEMES.contains(theme)) {
            throw new InvalidPreferenceException("theme must be one of " + THEMES);
        }

        preferencesMapper.upsertDisplay(accountId, accountId, currency, theme);
        return toResponse(accountId, preferencesMapper.findByAccountId(accountId));
    }

    public PreferenceResponse updateAvatar(Long accountId, Long tokenAccountId,
                                           UpdateAvatarPreferenceRequest request) {
        requireOwnership(accountId, tokenAccountId);

        // Trimmed but not upper-cased: the keys are lower-case, unlike the codes normalise() serves.
        String avatar = request.avatarKey() == null ? null : request.avatarKey().trim();
        if (avatar == null || !AVATAR_KEYS.contains(avatar)) {
            throw new InvalidPreferenceException("avatarKey must be one of " + AVATAR_KEYS);
        }

        preferencesMapper.upsertAvatar(accountId, accountId, avatar);
        return toResponse(accountId, preferencesMapper.findByAccountId(accountId));
    }

    /**
     * The in-process seam for notifications. Never refuses and never returns null: an account with no
     * stored preference resolves to the platform default channel, so a trade notification is never
     * lost because the customer never opened the settings screen.
     */
    @Override
    public NotificationRoute resolveChannel(long accountId) {
        CustomerPreferenceRow row = preferencesMapper.findByAccountId(accountId);
        if (row == null) {
            return new NotificationRoute(DEFAULT_CHANNEL, null);
        }
        String contact = switch (row.getNotificationChannel()) {
            case "EMAIL" -> row.getContactEmail();
            case "SMS" -> row.getContactPhone();
            default -> null;
        };
        return new NotificationRoute(row.getNotificationChannel(), contact);
    }

    private void requireOwnership(Long accountId, Long tokenAccountId) {
        // Same refusal the account routes give, so a probe for another customer's preferences cannot
        // be told apart from any other forbidden account access.
        if (accountId == null || !accountId.equals(tokenAccountId)) {
            throw new AccountNotActiveException("account is not active");
        }
    }

    private PreferenceResponse toResponse(Long accountId, CustomerPreferenceRow row) {
        if (row == null) {
            // No row yet: the documented defaults, with a null updatedOn so the UI shows "not set yet".
            return new PreferenceResponse(accountId, accountId, DEFAULT_CHANNEL, null, null,
                    DEFAULT_CURRENCY, DEFAULT_THEME, DEFAULT_AVATAR, null);
        }
        return new PreferenceResponse(row.getAccountId(), row.getDefaultAccountId(),
                row.getNotificationChannel(), row.getContactEmail(), row.getContactPhone(),
                row.getDisplayCurrency(), row.getTheme(), row.getAvatarKey(), row.getUpdatedOn());
    }

    private static String normalise(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}
