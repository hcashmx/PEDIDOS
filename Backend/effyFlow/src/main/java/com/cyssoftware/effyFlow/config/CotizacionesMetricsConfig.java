package com.cyssoftware.effyFlow.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CotizacionesMetricsConfig {
    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    MeterRegistry cotizacionesMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}
