package jp.aegif.nemaki.rest.controller;

import jp.aegif.nemaki.util.PropertyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configurable CORS for Spring MVC API endpoints.
 *
 * <p>Reads allowed origins from {@code api.cors.allowedOrigins} property.
 * Defaults to {@code *} (all origins) for development convenience.
 * In production, set to the actual frontend origin (e.g., {@code https://ecm.example.com}).
 *
 * <p>Note: {@code allow-credentials} is always {@code false} because CSRF
 * protection handles ambient credentials (cookies, Basic auth) separately.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(CorsConfig.class);

    @Autowired(required = false)
    private PropertyManager propertyManager;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String origins = "*";
        if (propertyManager != null) {
            String configured = propertyManager.readValue("api.cors.allowedOrigins");
            if (configured != null && !configured.isBlank()) {
                origins = configured.trim();
            }
        }

        logger.info("CORS allowed origins for /v1/**: {}", origins);

        CorsRegistration reg = registry.addMapping("/v1/**")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);

        if ("*".equals(origins)) {
            reg.allowedOrigins("*");
        } else {
            // Support comma-separated multiple origins
            reg.allowedOrigins(origins.split("\\s*,\\s*"));
        }
    }
}
