package io.github.cn3shy.text2img.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.cn3shy.text2img.config.ComfyUiProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class WorkflowBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkflowBuilder builder = new WorkflowBuilder(mapper, properties(), new DefaultResourceLoader());

    @Test
    void writesPromptToStringBOfNode84() {
        ObjectNode workflow = builder.build("a cat on a sofa", 1024, 1024);

        assertEquals("a cat on a sofa",
                workflow.path("84").path("inputs").path("string_b").asString());
    }

    @Test
    void keepsStylePrefixInStringA() {
        ObjectNode workflow = builder.build("a cat on a sofa", 1024, 1024);

        assertEquals(" dreamcos style, cosplay photo",
                workflow.path("84").path("inputs").path("string_a").asString());
    }

    @Test
    void latentNodeUsesExactSizeAndResolutionSelectorIsRemoved() {
        ObjectNode workflow = builder.build("cat", 768, 1024);

        assertEquals(768, workflow.path("81").path("inputs").path("width").asInt());
        assertEquals(1024, workflow.path("81").path("inputs").path("height").asInt());
        assertFalse(workflow.has("83"));
    }

    @Test
    void samplerGetsARandomSeedPerBuild() {
        long first = builder.build("cat", 1024, 1024).path("70").path("inputs").path("seed").asLong();
        long second = builder.build("cat", 1024, 1024).path("70").path("inputs").path("seed").asLong();

        assertNotEquals(first, second);
    }

    private static ComfyUiProperties properties() {
        return new ComfyUiProperties("http://127.0.0.1:8188",
                Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofSeconds(100),
                Duration.ofMinutes(5), Duration.ofMillis(500), "classpath:Krea2-t2i.json");
    }
}
