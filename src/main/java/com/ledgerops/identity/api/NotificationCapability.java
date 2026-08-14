package com.ledgerops.identity.api;

/**
 * Identity capabilities used by derived Notification recipient selection.
 *
 * <p>The Notification module asks Identity for current authority through this
 * small boundary instead of reading membership or user tables directly.</p>
 */
public enum NotificationCapability {
    RISK_READ,
    CASE_READ,
    NOTIFICATION_READ
}
