package io.github.cn3shy.text2img.workflow;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.io.Resource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns the editable ComfyUI workflow export into an API workflow for one request.
 *
 * <p>Customisable nodes:
 * <ul>
 *     <li>{@code 84} (StringConcatenate): the user prompt goes into {@code string_b};</li>
 *     <li>{@code 81} (EmptySD3LatentImage): exact {@code width}/{@code height};</li>
 *     <li>{@code 70} (KSampler): a fresh random seed on every request.</li>
 * </ul>
 */
public class WorkflowBuilder {

    private static final String POSITIVE_CONCAT_NODE = "84";
    private static final String LATENT_NODE = "81";
    private static final String SAMPLER_NODE = "70";
    private static final String RESOLUTION_SELECTOR_NODE = "83";

    private final ObjectMapper mapper;
    private final byte[] template;

    public WorkflowBuilder(ObjectMapper mapper, Resource resource) {
        this.mapper = mapper;
        this.template = readTemplate(mapper, resource);
    }

    public ObjectNode build(String prompt, int width, int height) {
        ObjectNode workflow = (ObjectNode) mapper.readTree(template);

        ObjectNode positiveInputs = inputsOf(workflow, POSITIVE_CONCAT_NODE);
        positiveInputs.put("string_b", prompt);

        // The exported workflow derives width/height from a ResolutionSelector.
        // Overwrite the latent node with the exact size requested by the API and
        // drop the selector so ComfyUI never uses an approximation of it.
        ObjectNode latentInputs = inputsOf(workflow, LATENT_NODE);
        latentInputs.put("width", width);
        latentInputs.put("height", height);
        workflow.remove(RESOLUTION_SELECTOR_NODE);

        ObjectNode samplerInputs = inputsOf(workflow, SAMPLER_NODE);
        samplerInputs.put("seed", ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE));

        return workflow;
    }

    private static byte[] readTemplate(ObjectMapper mapper, Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            JsonNode root = mapper.readTree(bytes);
            if (!root.isObject()) {
                throw new IllegalStateException("ComfyUI 工作流模板不是 JSON 对象: " + resource);
            }
            for (String nodeId : new String[] {POSITIVE_CONCAT_NODE, LATENT_NODE, SAMPLER_NODE}) {
                if (!root.path(nodeId).path("inputs").isObject()) {
                    throw new IllegalStateException(
                            "ComfyUI 工作流模板缺少节点 " + nodeId + ": " + resource);
                }
            }
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 ComfyUI 工作流模板: " + resource, e);
        }
    }

    private static ObjectNode inputsOf(ObjectNode workflow, String nodeId) {
        return (ObjectNode) workflow.path(nodeId).path("inputs");
    }
}
