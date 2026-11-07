package io.github.cn3shy.text2img.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.cn3shy.text2img.config.ComfyUiProperties;
import io.github.cn3shy.text2img.error.CapacityExceededException;
import io.github.cn3shy.text2img.web.Text2ImgRequest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GenerationJobServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ComfyUiText2ImgService comfyUi = mock(ComfyUiText2ImgService.class);
    private final CountDownLatch block = new CountDownLatch(1);
    private GenerationJobService service;

    private GenerationJobService service(Duration queueTimeout, boolean blockHistory) {
        ComfyUiProperties properties = new ComfyUiProperties("http://127.0.0.1:1",
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(30),
                queueTimeout, Duration.ofMillis(10), "classpath:Krea2-t2i.json");
        when(comfyUi.prepare(any())).thenReturn(new PreparedWorkflow(mapper.createObjectNode(), Map.of()));
        when(comfyUi.submit(any(), anyString())).thenReturn("prompt-1");
        when(comfyUi.isRunning(anyString())).thenReturn(false);
        when(comfyUi.fetchHistory(anyString())).thenAnswer(invocation -> {
            if (blockHistory) {
                try {
                    block.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return mapper.createObjectNode();
        });
        service = new GenerationJobService(comfyUi, properties, new GenerationJobStore(), mapper,
                new GenerationCapacity());
        return service;
    }

    @AfterEach
    void tearDown() {
        block.countDown();
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void fifthConcurrentSubmitIsRejected() {
        service(Duration.ofMillis(100), true);
        for (int i = 0; i < 4; i++) {
            service.submit(new Text2ImgRequest("cat", 1024, 1024));
        }

        assertThrows(CapacityExceededException.class,
                () -> service.submit(new Text2ImgRequest("cat", 1024, 1024)));
    }

    @Test
    void queuedJobFailsWithQueueTimeoutMessage() throws Exception {
        service(Duration.ofMillis(50), false);
        GenerationJob job = service.submit(new Text2ImgRequest("cat", 1024, 1024));

        long deadline = System.currentTimeMillis() + 5_000;
        while (job.status() == GenerationJob.Status.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(GenerationJob.Status.FAILED, job.status());
        assertEquals("排队超时，请稍后重试", job.message());
    }
}
