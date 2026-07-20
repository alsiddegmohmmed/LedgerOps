package com.ledgerops.risk.infrastructure;

import com.ledgerops.risk.domain.RiskEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RiskModuleConfiguration {

    @Bean
    RiskEvaluator riskEvaluator() {
        return new RiskEvaluator();
    }
}
