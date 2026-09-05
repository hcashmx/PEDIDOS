package com.cyssoftware.effyFlow;


import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import java.nio.charset.StandardCharsets;

@Configuration
public class JacksonConfig {

    // Asegura que todas las respuestas JSON usen UTF-8
    public JacksonConfig(MappingJackson2HttpMessageConverter converter) {
        converter.setDefaultCharset(StandardCharsets.UTF_8);
    }
}