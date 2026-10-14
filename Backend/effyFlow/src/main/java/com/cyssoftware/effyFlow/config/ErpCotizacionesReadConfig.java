package com.cyssoftware.effyFlow.config;

import com.cyssoftware.effyFlow.service.cotizaciones.ErpCotizacionesReadClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ErpCotizacionesReadConfig {
    @Bean("erpCotizacionesReadClient")
    ErpCotizacionesReadClient erpCotizacionesReadClient(
            @Value("${erp.base-url:}") String url,
            @Value("${erp.cotizaciones.read.username:}") String username,
            @Value("${erp.cotizaciones.read.password:}") String password,
            @Value("${erp.cotizaciones.read.technical-username:}") String technicalUsername,
            @Value("${sync.cotizaciones.read.enabled:false}") boolean enabled,
            @Value("${erp.connect-timeout-ms:3000}") int connect,
            @Value("${erp.read-timeout-ms:15000}") int read) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connect))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofMillis(read));
        // Keep this client's timeouts and transport isolated from the shared Pedido RestClient.Builder.
        return new ErpCotizacionesReadClient(RestClient.builder().requestFactory(factory).build(), url,
                username, password, technicalUsername, enabled);
    }
}
