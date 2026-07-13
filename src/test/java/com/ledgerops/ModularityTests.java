package com.ledgerops;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    void verifiesApplicationModuleBoundaries() {
        ApplicationModules.of(LedgerOpsApplication.class).verify();
    }
}
