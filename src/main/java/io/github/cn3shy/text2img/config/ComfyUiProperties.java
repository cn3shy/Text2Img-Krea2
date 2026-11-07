package io.github.cn3shy.text2img.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "comfyui")
public record ComfyUiProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration generationTimeout,
        Duration pollInterval,
        String workflow) {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8188";
    private static final String DEFAULT_WORKFLOW = "classpath:Krea2 t2i.json";

    public ComfyUiProperties {
        baseUrl = normalizeBaseUrl(baseUrl);
        connectTimeout = defaultDuration(connectTimeout, Duration.ofSeconds(5));
        readTimeout = defaultDuration(readTimeout, Duration.ofSeconds(60));
        generationTimeout = defaultDuration(generationTimeout, Duration.ofSeconds(100));
        pollInterval = defaultDuration(pollInterval, Duration.ofMillis(500));
        if (pollInterval.compareTo(Duration.ofMillis(10)) < 0) {
            pollInterval = Duration.ofMillis(10);
        }
        workflow = (workflow == null || workflow.isBlank()) ? DEFAULT_WORKFLOW : workflow;
    }

    private static String normalizeBaseUrl(String value) {
        String url = (value == null || value.isBlank()) ? DEFAULT_BASE_URL : value.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static Duration defaultDuration(Duration value, Duration fallback) {
        return (value == null || value.isZero() || value.isNegative()) ? fallback : value;
    }
}
