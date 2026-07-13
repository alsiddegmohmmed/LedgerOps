package com.ledgerops.tenancy.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Currency;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantTests {

    @Test
    void createsTenantWithRequiredAttributes() {
        TenantId tenantId =
                TenantId.from(UUID.fromString("3d6f0d8a-b772-4f39-8130-bd235dc87da7"));

        Tenant tenant = new Tenant(
                tenantId,
                "  Acme Payments  ",
                Currency.getInstance("SAR"),
                Locale.forLanguageTag("en-SA"),
                TenantStatus.PENDING_ACTIVATION
        );

        assertEquals(tenantId, tenant.id());
        assertEquals("Acme Payments", tenant.name());
        assertEquals(Currency.getInstance("SAR"), tenant.defaultCurrency());
        assertEquals(Locale.forLanguageTag("en-SA"), tenant.defaultLocale());
        assertEquals(TenantStatus.PENDING_ACTIVATION, tenant.status());
    }

    @Test
    void rejectsBlankTenantName() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new Tenant(
                        TenantId.newId(),
                        "   ",
                        Currency.getInstance("SAR"),
                        Locale.forLanguageTag("en-SA"),
                        TenantStatus.PENDING_ACTIVATION
                )
        );

        assertEquals("Tenant name must not be blank", exception.getMessage());
    }

    @Test
    void rejectsNullTenantId() {
        assertThrows(
                NullPointerException.class,
                () -> new Tenant(
                        null,
                        "Acme Payments",
                        Currency.getInstance("SAR"),
                        Locale.forLanguageTag("en-SA"),
                        TenantStatus.PENDING_ACTIVATION
                )
        );
    }
}
