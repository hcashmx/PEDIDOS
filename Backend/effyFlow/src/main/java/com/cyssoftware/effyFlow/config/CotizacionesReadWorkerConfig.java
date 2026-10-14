package com.cyssoftware.effyFlow.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "sync.cotizaciones.read.enabled", havingValue = "true")
public class CotizacionesReadWorkerConfig { }
