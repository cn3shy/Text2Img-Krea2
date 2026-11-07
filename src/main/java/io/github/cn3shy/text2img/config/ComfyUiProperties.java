package io.github.cn3shy.text2img.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ComfyUI 相关配置，对应 application.yaml 里的 {@code comfyui.*}。
 *
 * <p>所有配置都有默认值，本地不写也能直接启动：
 * <ul>
 *   <li>{@code base-url}：ComfyUI 地址，默认 {@code http://127.0.0.1:8188}</li>
 *   <li>{@code connect-timeout}：建立连接超时，默认 5 秒</li>
 *   <li>{@code read-timeout}：单次读取响应超时，默认 60 秒</li>
 *   <li>{@code generation-timeout}：真正开始执行后的最长等待时间，默认 100 秒</li>
 *   <li>{@code queue-timeout}：在 ComfyUI 队列里排队的最长时间，默认 5 分钟</li>
 *   <li>{@code poll-interval}：轮询 /history 的间隔，默认 500 毫秒</li>
 *   <li>{@code workflow}：工作流模板位置，默认 {@code classpath:Krea2-t2i.json}</li>
 * </ul>
 *
 * <p>下面的紧凑构造器负责补默认值和规范化；Spring Boot 会按构造器参数名绑定配置。
 */
@ConfigurationProperties(prefix = "comfyui")
public record ComfyUiProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration generationTimeout,
        Duration queueTimeout,
        Duration pollInterval,
        String workflow) {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8188";
    private static final String DEFAULT_WORKFLOW = "classpath:Krea2-t2i.json";

    public ComfyUiProperties {
        baseUrl = normalizeBaseUrl(baseUrl);
        connectTimeout = defaultDuration(connectTimeout, Duration.ofSeconds(5));
        readTimeout = defaultDuration(readTimeout, Duration.ofSeconds(60));
        generationTimeout = defaultDuration(generationTimeout, Duration.ofSeconds(100));
        queueTimeout = defaultDuration(queueTimeout, Duration.ofMinutes(5));
        pollInterval = defaultDuration(pollInterval, Duration.ofMillis(500));
        // 轮询间隔太小会把 ComfyUI 打满，兜底限制为至少 10ms
        if (pollInterval.compareTo(Duration.ofMillis(10)) < 0) {
            pollInterval = Duration.ofMillis(10);
        }
        workflow = (workflow == null || workflow.isBlank()) ? DEFAULT_WORKFLOW : workflow;
    }

    /** 去掉结尾的斜杠，避免拼出 http://host:8188//prompt 这种地址。 */
    private static String normalizeBaseUrl(String value) {
        String url = (value == null || value.isBlank()) ? DEFAULT_BASE_URL : value.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** 空值、0 或负数都回退到默认值。 */
    private static Duration defaultDuration(Duration value, Duration fallback) {
        return (value == null || value.isZero() || value.isNegative()) ? fallback : value;
    }
}
