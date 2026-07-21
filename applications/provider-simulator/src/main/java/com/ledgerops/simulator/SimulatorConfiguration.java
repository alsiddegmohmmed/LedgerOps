package com.ledgerops.simulator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class SimulatorConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
