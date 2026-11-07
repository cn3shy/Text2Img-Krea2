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
 * {@code /text2img} 的跨域配置。
 *
 * <p>经 Vite/Vercel 代理访问时浏览器是同源请求，不会触发 CORS；只有前端直连后端时才会用到。
 * 默认放行所有来源，方便 localhost:5173、5174、局域网 IP 等各种开发环境；
 * 线上可以通过环境变量 {@code CORS_ALLOWED_ORIGINS} 传逗号分隔的白名单收紧。
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
