package com.ledgerops.identity.api;

import java.util.List;
import java.util.UUID;

/**
 * Read-only Identity boundary for current Notification recipient authority.
 */
public interface NotificationRecipientQueryPort {

    /**
     * Returns active users who currently hold the requested capability for the
     * Tenant and, when supplied, the specific Merchant.
     */
    List<NotificationRecipient> findRecipients(
            UUID tenantId,
            UUID merchantId,
            NotificationCapability capability
    );
}
