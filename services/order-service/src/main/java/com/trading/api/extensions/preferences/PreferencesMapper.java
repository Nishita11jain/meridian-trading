package com.trading.api.extensions.preferences;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Persistence for customer_preferences. Two separate upserts, one per settings group, so that saving
 * the notification settings never overwrites the display settings and vice versa: each statement
 * touches only its own columns and leaves the rest at their stored value (or the table default on a
 * first insert).
 */
@Mapper
public interface PreferencesMapper {

    CustomerPreferenceRow findByAccountId(@Param("accountId") Long accountId);

    int upsertNotification(
            @Param("accountId") Long accountId,
            @Param("defaultAccountId") Long defaultAccountId,
            @Param("notificationChannel") String notificationChannel,
            @Param("contactEmail") String contactEmail,
            @Param("contactPhone") String contactPhone);

    int upsertDisplay(
            @Param("accountId") Long accountId,
            @Param("defaultAccountId") Long defaultAccountId,
            @Param("displayCurrency") String displayCurrency,
            @Param("theme") String theme);

    /** Touches only the avatar column, like the two upserts above. */
    int upsertAvatar(
            @Param("accountId") Long accountId,
            @Param("defaultAccountId") Long defaultAccountId,
            @Param("avatarKey") String avatarKey);
}
