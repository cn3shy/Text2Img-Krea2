package io.github.cn3shy.text2img;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * text2img 后端启动类。
 *
 * <p>{@code @ConfigurationPropertiesScan} 会自动扫描并注册
 * {@link io.github.cn3shy.text2img.config.ComfyUiProperties}，
 * 所以不用再手写 {@code @EnableConfigurationProperties}。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Text2imgApplication {

    private static final Logger log = LoggerFactory.getLogger(Text2imgApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(Text2imgApplication.class, args);
        // Spring Boot 自身会打印启动日志，这里再留一行方便确认进程已经就绪
        log.info("text2img 后端启动完成，接口: POST http://localhost:8090/text2img");
    }
}
