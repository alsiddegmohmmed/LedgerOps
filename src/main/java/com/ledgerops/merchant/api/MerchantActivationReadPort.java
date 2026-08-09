package com.ledgerops.merchant.api;

import com.ledgerops.tenancy.api.TenantReference;

public interface MerchantActivationReadPort {

    boolean hasActiveMerchant(TenantReference tenant);
}
