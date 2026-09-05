package com.cyssoftware.effyFlow.config;


import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // Aplica a todos los endpoints
                .allowedOrigins("*") // Permite CUALQUIER origen. ¡Usar solo en desarrollo!
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
                // Nota: no se puede usar allowCredentials(true) junto con allowedOrigins("*") 
                // para evitar problemas, aunque se deja para la configuración final del usuario.
                //.allowCredentials(true);
    }
}
