package io.github.cn3shy.text2img.config;

import io.github.cn3shy.text2img.service.ComfyUiText2ImgService;
import io.github.cn3shy.text2img.service.Text2ImgService;
import io.github.cn3shy.text2img.workflow.WorkflowBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(ComfyUiProperties.class)
public class Text2ImgConfiguration {

    @Bean
    public WorkflowBuilder workflowBuilder(ObjectMapper objectMapper, ComfyUiProperties properties,
                                           ResourceLoader resourceLoader) {
        return new WorkflowBuilder(objectMapper, resourceLoader.getResource(properties.workflow()));
    }

    @Bean
    public Text2ImgService text2ImgService(WorkflowBuilder workflowBuilder, ComfyUiProperties properties,
                                           ObjectMapper objectMapper) {
        return new ComfyUiText2ImgService(workflowBuilder, properties, objectMapper, RestClient.builder());
    }
}
