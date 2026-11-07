package io.github.cn3shy.text2img.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS for the {@code /text2img} endpoint.
 *
 * <p>Through the Vite/Vercel proxy the browser request is same-origin, so CORS is
 * not involved. This only matters when the frontend calls the backend directly.
 * The default {@code *} lets any dev origin (localhost:5173, :5174, LAN IP, …)
 * work; set {@code CORS_ALLOWED_ORIGINS} to a comma separated list in production.
 */
@Configuration
public class CorsConfig {

    private static final List<String> ALLOWED_METHODS = List.of("POST", "OPTIONS");

    private final List<String> allowedOriginPatterns;

    public CorsConfig(@Value("${app.cors.allowed-origins:*}") String allowedOrigins) {
        this.allowedOriginPatterns = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(allowedOriginPatterns);
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/text2img", configuration);
        return new CorsFilter(source);
    }
}
