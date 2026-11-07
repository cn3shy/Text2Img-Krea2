package io.github.cn3shy.text2img.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 应用级 Bean 配置。
 *
 * <p>{@link io.github.cn3shy.text2img.workflow.WorkflowBuilder} 和
 * {@link io.github.cn3shy.text2img.service.ComfyUiText2ImgService} 已经用
 * {@code @Component}/{@code @Service} 交给组件扫描，这里只负责创建
 * 带连接/读取超时的 ComfyUI {@link RestClient}。
 */
@Configuration
public class Text2ImgConfiguration {

    /**
     * ComfyUI HTTP 客户端。
     *
     * <ul>
     *   <li>{@code connectTimeout}：建立 TCP 连接的超时；</li>
     *   <li>{@code readTimeout}：单次读取响应的超时，轮询 /history 和下载图片都受它限制。</li>
     * </ul>
     */
    @Bean
    public RestClient comfyUiRestClient(ComfyUiProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
