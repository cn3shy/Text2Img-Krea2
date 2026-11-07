package io.github.cn3shy.text2img.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ComfyUiProgressListenerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GenerationJob job = new GenerationJob("job-1", 1024, 1024, System::currentTimeMillis);

    private ComfyUiProgressListener listener() {
        job.setPromptId("prompt-1");
        return new ComfyUiProgressListener(job, Map.of("70", "采样中", "75", "解码图像"), mapper);
    }

    @Test
    void progressUpdatesPercentAndStage() {
        ComfyUiProgressListener listener = listener();

        listener.handleText(
                "{\"type\":\"progress\",\"data\":{\"value\":3,\"max\":8,\"prompt_id\":\"prompt-1\",\"node\":\"70\"}}");

        assertEquals(38, job.progress());
        assertEquals("采样中 3/8", job.stage());
    }

    @Test
    void ignoresProgressForOtherPrompt() {
        ComfyUiProgressListener listener = listener();

        listener.handleText(
                "{\"type\":\"progress\",\"data\":{\"value\":3,\"max\":8,\"prompt_id\":\"other\",\"node\":\"70\"}}");

        assertNull(job.progress());
    }

    @Test
    void executingUpdatesStageAndMarksExecutionStart() {
        ComfyUiProgressListener listener = listener();

        listener.handleText("{\"type\":\"executing\",\"data\":{\"node\":\"75\",\"prompt_id\":\"prompt-1\"}}");

        assertEquals("解码图像", job.stage());
        assertTrue(job.executedAtMillis() > 0);
    }
}
