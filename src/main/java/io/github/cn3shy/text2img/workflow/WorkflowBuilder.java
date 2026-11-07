package io.github.cn3shy.text2img.workflow;

import io.github.cn3shy.text2img.config.ComfyUiProperties;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 把 ComfyUI 导出的工作流模板转换成"本次请求专用"的工作流。
 *
 * <p>模板在启动时读入内存（字节数组），每次请求重新解析一份，互不影响，线程安全。
 * 需要动态替换的节点：
 * <ul>
 *   <li>{@code 79}（CLIPTextEncode）：用户 prompt 直接写入 {@code text}；</li>
 *   <li>{@code 81}（EmptySD3LatentImage）：写入精确的 {@code width}/{@code height}；</li>
 *   <li>{@code 70}（KSampler）：每次随机 seed，避免重复出图；</li>
 *   <li>{@code 83}（ResolutionSelector）：被 81 覆盖后不再需要，直接删除。</li>
 * </ul>
 *
 * <p>类上标了 {@code @Component}，由 Spring 组件扫描创建；类里只有一个构造器，
 * Spring 4.3+ 会自动完成参数注入，所以不需要写 {@code @Autowired}。
 */
@Component
public class WorkflowBuilder {

    private static final String POSITIVE_PROMPT_NODE = "79";
    private static final String LATENT_NODE = "81";
    private static final String SAMPLER_NODE = "70";
    private static final String RESOLUTION_SELECTOR_NODE = "83";

    private final ObjectMapper mapper;
    /** 启动时一次性读入的模板内容，避免每个请求都读磁盘。 */
    private final byte[] template;

    public WorkflowBuilder(ObjectMapper mapper, ComfyUiProperties properties, ResourceLoader resourceLoader) {
        this.mapper = mapper;
        this.template = readTemplate(mapper, resourceLoader.getResource(properties.workflow()));
    }

    /**
     * 生成一份该请求专属的 workflow。
     *
     * @param prompt 用户输入的正向提示词
     * @param width  图片宽度（必须是 8 的倍数）
     * @param height 图片高度（必须是 8 的倍数）
     */
    public ObjectNode build(String prompt, int width, int height) {
        // 每次从模板字节重新解析，得到独立的对象树，多个请求并发也不会互相污染
        ObjectNode workflow = (ObjectNode) mapper.readTree(template);

        // 79 号 CLIPTextEncode：正向提示词直接写入 text
        ObjectNode positiveInputs = inputsOf(workflow, POSITIVE_PROMPT_NODE);
        positiveInputs.put("text", prompt);

        // 模板通过 83 号 ResolutionSelector 按比例算尺寸，这里改成 API 传来的精确像素，
        // 并删掉 83 号节点，避免 ComfyUI 使用近似尺寸。
        ObjectNode latentInputs = inputsOf(workflow, LATENT_NODE);
        latentInputs.put("width", width);
        latentInputs.put("height", height);
        workflow.remove(RESOLUTION_SELECTOR_NODE);

        // 70 号 KSampler：随机种子，让相同 prompt 也能出不同的图
        ObjectNode samplerInputs = inputsOf(workflow, SAMPLER_NODE);
        samplerInputs.put("seed", ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE));

        return workflow;
    }

    /**
     * 读取并校验模板：必须是 JSON 对象，且包含所有需要改写的节点。
     * 启动时就失败，比运行到一半才发现配置错误更容易排查。
     */
    private static byte[] readTemplate(ObjectMapper mapper, Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            JsonNode root = mapper.readTree(bytes);
            if (!root.isObject()) {
                throw new IllegalStateException("ComfyUI 工作流模板不是 JSON 对象: " + resource);
            }
            for (String nodeId : new String[] {POSITIVE_PROMPT_NODE, LATENT_NODE, SAMPLER_NODE}) {
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

    /** 取出指定节点的 inputs 对象，节点结构在构造时已经校验过。 */
    private static ObjectNode inputsOf(ObjectNode workflow, String nodeId) {
        return (ObjectNode) workflow.path(nodeId).path("inputs");
    }
}
