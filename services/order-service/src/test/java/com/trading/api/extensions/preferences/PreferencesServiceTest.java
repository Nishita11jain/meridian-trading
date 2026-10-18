package com.trading.api.extensions.preferences;

import com.trading.api.mapper.InstrumentMapper;
import com.trading.domain.exception.AccountNotActiveException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The preferences service over a stand-in mapper and instrument catalogue: the defaults when nothing
 * is stored, the validation rules, the cross-account refusal, and the channel resolution the
 * notifications module depends on.
 */
class PreferencesServiceTest {

    private static final long ACCOUNT = 4L;
    private static final long OTHER = 5L;

    private PreferencesMapper preferencesMapper;
    private InstrumentMapper instrumentMapper;
    private PreferencesService service;

    @BeforeEach
    void setUp() {
        preferencesMapper = mock(PreferencesMapper.class);
        instrumentMapper = mock(InstrumentMapper.class);
        when(instrumentMapper.findDistinctCurrencies()).thenReturn(List.of("INR", "USD"));
        service = new PreferencesService(preferencesMapper, instrumentMapper);
    }

    private static CustomerPreferenceRow row(String channel, String email, String phone, String currency, String theme) {
        CustomerPreferenceRow row = new CustomerPreferenceRow();
        row.setAccountId(ACCOUNT);
        row.setDefaultAccountId(ACCOUNT);
        row.setNotificationChannel(channel);
        row.setContactEmail(email);
        row.setContactPhone(phone);
        row.setDisplayCurrency(currency);
        row.setTheme(theme);
        row.setUpdatedOn(Instant.parse("2026-10-01T00:00:00Z"));
        return row;
    }

    @Nested
    class Reading {

        @Test
        void returnsPlatformDefaultsWhenNothingStored() {
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(null);

            PreferenceResponse response = service.getPreferences(ACCOUNT, ACCOUNT);

            assertEquals("IN_APP", response.notificationChannel());
            assertEquals("INR", response.displayCurrency());
            assertEquals("SYSTEM", response.theme());
            assertEquals(ACCOUNT, response.defaultAccountId());
            assertNull(response.updatedOn(), "a null timestamp tells the UI nothing has been saved yet");
        }

        @Test
        void returnsTheStoredRowWhenOneExists() {
            when(preferencesMapper.findByAccountId(ACCOUNT))
                    .thenReturn(row("EMAIL", "a@b.com", null, "USD", "DARK"));

            PreferenceResponse response = service.getPreferences(ACCOUNT, ACCOUNT);

            assertEquals("EMAIL", response.notificationChannel());
            assertEquals("a@b.com", response.contactEmail());
            assertEquals("USD", response.displayCurrency());
            assertEquals("DARK", response.theme());
        }
    }

    @Nested
    class AccessControl {

        @Test
        void readingAnotherAccountIsRefusedWithAcc403() {
            AccountNotActiveException refusal = assertThrows(AccountNotActiveException.class,
                    () -> service.getPreferences(OTHER, ACCOUNT));

            assertEquals("ACC-403", refusal.code());
            verify(preferencesMapper, never()).findByAccountId(any());
        }

        @Test
        void writingAnotherAccountIsRefusedAndStoresNothing() {
            assertThrows(AccountNotActiveException.class, () -> service.updateDisplay(OTHER, ACCOUNT,
                    new UpdateDisplayPreferenceRequest("USD", "DARK")));

            verify(preferencesMapper, never()).upsertDisplay(any(), any(), any(), any());
        }
    }

    @Nested
    class NotificationUpdates {

        @Test
        void storesAValidChannelAndReturnsTheSavedRow() {
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(row("SMS", null, "+91999", "INR", "SYSTEM"));

            PreferenceResponse response = service.updateNotification(ACCOUNT, ACCOUNT,
                    new UpdateNotificationPreferenceRequest("sms", null, "+91999"));

            assertEquals("SMS", response.notificationChannel());
            verify(preferencesMapper).upsertNotification(ACCOUNT, ACCOUNT, "SMS", null, "+91999");
        }

        @Test
        void refusesAChannelOutsideTheAllowedSet() {
            InvalidPreferenceException invalid = assertThrows(InvalidPreferenceException.class,
                    () -> service.updateNotification(ACCOUNT, ACCOUNT,
                            new UpdateNotificationPreferenceRequest("CARRIER_PIGEON", null, null)));

            assertEquals("VAL-422", invalid.code());
            verify(preferencesMapper, never()).upsertNotification(any(), any(), any(), any(), any());
        }

        @Test
        void refusesEmailChannelWithoutAnEmail() {
            assertThrows(InvalidPreferenceException.class, () -> service.updateNotification(ACCOUNT, ACCOUNT,
                    new UpdateNotificationPreferenceRequest("EMAIL", "  ", null)));

            verify(preferencesMapper, never()).upsertNotification(any(), any(), any(), any(), any());
        }

        @Test
        void refusesSmsChannelWithoutAPhone() {
            assertThrows(InvalidPreferenceException.class, () -> service.updateNotification(ACCOUNT, ACCOUNT,
                    new UpdateNotificationPreferenceRequest("SMS", null, null)));
        }

        @Test
        void inAppNeedsNoContactDetail() {
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(row("IN_APP", null, null, "INR", "SYSTEM"));

            service.updateNotification(ACCOUNT, ACCOUNT, new UpdateNotificationPreferenceRequest("IN_APP", null, null));

            verify(preferencesMapper).upsertNotification(eq(ACCOUNT), eq(ACCOUNT), eq("IN_APP"), isNull(), isNull());
        }
    }

    @Nested
    class DisplayUpdates {

        @Test
        void storesACurrencyThePlatformTradesIn() {
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(row("IN_APP", null, null, "USD", "LIGHT"));

            PreferenceResponse response = service.updateDisplay(ACCOUNT, ACCOUNT,
                    new UpdateDisplayPreferenceRequest("usd", "light"));

            assertEquals("USD", response.displayCurrency());
            verify(preferencesMapper).upsertDisplay(ACCOUNT, ACCOUNT, "USD", "LIGHT");
        }

        @Test
        void refusesACurrencyThePlatformDoesNotTradeIn() {
            InvalidPreferenceException invalid = assertThrows(InvalidPreferenceException.class,
                    () -> service.updateDisplay(ACCOUNT, ACCOUNT, new UpdateDisplayPreferenceRequest("JPY", "DARK")));

            assertEquals("VAL-422", invalid.code());
            verify(preferencesMapper, never()).upsertDisplay(any(), any(), any(), any());
        }

        @Test
        void refusesAThemeThatIsNotOurs() {
            assertThrows(InvalidPreferenceException.class, () -> service.updateDisplay(ACCOUNT, ACCOUNT,
                    new UpdateDisplayPreferenceRequest("INR", "NEON")));
        }
    }

    @Nested
    class ChannelResolution {

        @Test
        void resolvesToTheStoredChannelAndItsContact() {
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(row("EMAIL", "a@b.com", null, "INR", "SYSTEM"));

            CustomerChannelResolver.NotificationRoute route = service.resolveChannel(ACCOUNT);

            assertEquals("EMAIL", route.channel());
            assertEquals("a@b.com", route.contact());
        }

        @Test
        void resolvesToTheDefaultChannelWhenNothingStoredSoNoNotificationIsLost() {
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(null);

            CustomerChannelResolver.NotificationRoute route = service.resolveChannel(ACCOUNT);

            assertEquals("IN_APP", route.channel());
            assertNull(route.contact());
        }

        @Test
        void inAppResolvesWithNoExternalContactEvenIfOneWasStored() {
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(row("IN_APP", "a@b.com", "+91", "INR", "SYSTEM"));

            CustomerChannelResolver.NotificationRoute route = service.resolveChannel(ACCOUNT);

            assertEquals("IN_APP", route.channel());
            assertNull(route.contact());
            assertFalse("a@b.com".equals(route.contact()));
        }
    }

    @Nested
    class Avatar {

        @Test
        void storesAPredefinedAvatarAndReturnsIt() {
            CustomerPreferenceRow saved = row("IN_APP", null, null, "INR", "SYSTEM");
            saved.setAvatarKey("avatar_04");
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(saved);

            PreferenceResponse response = service.updateAvatar(ACCOUNT, ACCOUNT,
                    new UpdateAvatarPreferenceRequest("avatar_04"));

            verify(preferencesMapper).upsertAvatar(ACCOUNT, ACCOUNT, "avatar_04");
            assertEquals("avatar_04", response.avatarKey());
        }

        @Test
        void refusesAnAvatarKeyThatIsNotShipped() {
            assertThrows(InvalidPreferenceException.class,
                    () -> service.updateAvatar(ACCOUNT, ACCOUNT, new UpdateAvatarPreferenceRequest("avatar_09")));
            verify(preferencesMapper, never()).upsertAvatar(any(), any(), any());
        }

        @Test
        void refusesABlankAvatarKey() {
            assertThrows(InvalidPreferenceException.class,
                    () -> service.updateAvatar(ACCOUNT, ACCOUNT, new UpdateAvatarPreferenceRequest("  ")));
            verify(preferencesMapper, never()).upsertAvatar(any(), any(), any());
        }

        @Test
        void changingAnotherAccountsAvatarIsRefusedAndStoresNothing() {
            assertThrows(AccountNotActiveException.class,
                    () -> service.updateAvatar(ACCOUNT, OTHER, new UpdateAvatarPreferenceRequest("avatar_02")));
            verify(preferencesMapper, never()).upsertAvatar(any(), any(), any());
        }

        @Test
        void aCustomerWithNothingStoredGetsTheDefaultAvatar() {
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(null);

            assertEquals("avatar_01", service.getPreferences(ACCOUNT, ACCOUNT).avatarKey());
        }
    }
}
