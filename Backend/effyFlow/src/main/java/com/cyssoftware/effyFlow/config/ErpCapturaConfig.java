package com.cyssoftware.effyFlow.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import com.cyssoftware.effyFlow.service.pedido.ErpPedidoClient;

@Configuration
public class ErpCapturaConfig {
    @Bean
    ErpPedidoClient erpPedidoClient(RestClient.Builder builder,
            @Value("${erp.base-url:}") String url, @Value("${erp.token:}") String token,
            @Value("${erp.username:}") String username, @Value("${erp.password:}") String password,
            @Value("${erp.connect-timeout-ms:3000}") int connect,
            @Value("${erp.read-timeout-ms:15000}") int read) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connect)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofMillis(read));
        return new ErpPedidoClient(builder.requestFactory(factory).build(), url, token, username, password);
    }
}
