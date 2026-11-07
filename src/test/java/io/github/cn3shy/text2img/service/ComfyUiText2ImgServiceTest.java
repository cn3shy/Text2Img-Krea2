package io.github.cn3shy.text2img.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.github.cn3shy.text2img.config.ComfyUiProperties;
import io.github.cn3shy.text2img.workflow.WorkflowBuilder;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ComfyUiText2ImgServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ComfyUiProperties properties = new ComfyUiProperties("http://127.0.0.1:8188",
            Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofSeconds(100),
            Duration.ofMinutes(5), Duration.ofMillis(500), "classpath:Krea2-t2i.json");
    private final ComfyUiText2ImgService service = new ComfyUiText2ImgService(
            new WorkflowBuilder(mapper, properties, new DefaultResourceLoader()),
            properties, mapper, mock(RestClient.class));

    @Test
    void prepareMapsNodesToStageLabels() {
        PreparedWorkflow prepared = service.prepare(new GenerationRequest("cat", 1024, 1024));

        assertEquals("采样中", prepared.nodeStages().get("70"));
        assertEquals("解码图像", prepared.nodeStages().get("75"));
        assertEquals("保存图像", prepared.nodeStages().get("9"));
    }

    @Test
    void detectsPromptInQueueRunning() {
        JsonNode queue = mapper.readTree(
                "{\"queue_running\":[[0,\"p1\",{},{}]],\"queue_pending\":[[1,\"p2\",{},{}]]}");

        assertTrue(ComfyUiText2ImgService.isPromptRunning(queue, "p1"));
        assertFalse(ComfyUiText2ImgService.isPromptRunning(queue, "p2"));
        assertFalse(ComfyUiText2ImgService.isPromptRunning(queue, "p3"));
    }

    @Test
    void queueTimeoutDefaultsToFiveMinutes() {
        ComfyUiProperties defaults = new ComfyUiProperties(null, null, null, null, null, null, null);

        assertEquals(Duration.ofMinutes(5), defaults.queueTimeout());
    }
}
