package io.github.cn3shy.text2img.service;

import io.github.cn3shy.text2img.config.ComfyUiProperties;
import io.github.cn3shy.text2img.error.GenerationTimeoutException;
import io.github.cn3shy.text2img.error.UpstreamException;
import io.github.cn3shy.text2img.web.Text2ImgRequest;
import io.github.cn3shy.text2img.workflow.WorkflowBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 文字生图服务：同步调用 ComfyUI 完成一次出图。
 *
 * <p>调用链路：
 * <ol>
 *   <li>校验参数并生成 workflow；</li>
 *   <li>{@code POST /prompt} 提交任务，拿到 {@code prompt_id}；</li>
 *   <li>轮询 {@code GET /history/{prompt_id}} 直到任务完成；</li>
 *   <li>{@code GET /view} 下载生成的 PNG，直接返回给前端。</li>
 * </ol>
 *
 * <p>类上标了 {@code @Service}，交给 Spring 组件扫描注册。
 */
@Service
public class ComfyUiText2ImgService implements Text2ImgService {

    private static final Logger log = LoggerFactory.getLogger(ComfyUiText2ImgService.class);

    /** ComfyUI 的 client_id，仅用于标识请求来源，没有业务含义。 */
    private static final String CLIENT_ID = "text2img-backend";
    /** 记录上游错误信息时的最大长度，避免日志/响应里塞入超长 HTML。 */
    private static final int MAX_ERROR_BODY_LENGTH = 500;

    private final WorkflowBuilder workflowBuilder;
    private final ComfyUiProperties properties;
    private final ObjectMapper mapper;
    private final RestClient comfyUiRestClient;

    /**
     * 构造器注入。
     *
     * <p>注意这里没有写 {@code @Autowired}：Spring 4.3 之后，一个类只有一个构造器时
     * 会自动按参数类型注入，这个注解可以省略。相比字段注入（{@code @Autowired} 直接标在字段上），
     * 构造器注入的依赖是 final 的，对象创建完就不可变，也更容易做单元测试。
     */
    public ComfyUiText2ImgService(WorkflowBuilder workflowBuilder, ComfyUiProperties properties,
                                  ObjectMapper mapper, RestClient comfyUiRestClient) {
        this.workflowBuilder = workflowBuilder;
        this.properties = properties;
        this.mapper = mapper;
        this.comfyUiRestClient = comfyUiRestClient;
    }

    @Override
    public byte[] generate(Text2ImgRequest request) {
        // 1. 校验参数：prompt 必填且最多 1200 字符，尺寸缺省 1024x1024
        GenerationRequest generation = RequestValidator.validate(request);
        long startedAt = System.nanoTime();

        // 2. 从模板生成该请求专属的 workflow（prompt、尺寸、随机 seed）
        ObjectNode workflow = workflowBuilder.build(generation.prompt(), generation.width(), generation.height());

        // 3. 提交任务 → 等待完成 → 下载图片
        String promptId = submit(workflow);
        JsonNode historyEntry = awaitCompletion(promptId, startedAt);
        ImageReference image = findImage(historyEntry);
        byte[] png = download(image);

        log.info("ComfyUI 生成完成 promptId={} size={}x{} bytes={} elapsedMs={}",
                promptId, generation.width(), generation.height(), png.length,
                (System.nanoTime() - startedAt) / 1_000_000);
        return png;
    }

    /** 提交 workflow 到 ComfyUI，返回任务的 prompt_id。 */
    private String submit(ObjectNode workflow) {
        // ComfyUI 的 /prompt 接口要求 body 形如 {"prompt": {...}, "client_id": "..."}
        ObjectNode payload = mapper.createObjectNode();
        payload.set("prompt", workflow);
        payload.put("client_id", CLIENT_ID);
        String body = mapper.writeValueAsString(payload);

        String response;
        try {
            response = comfyUiRestClient.post()
                    .uri("/prompt")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // ComfyUI 返回 4xx/5xx：通常是工作流节点校验失败
            throw new UpstreamException("ComfyUI 拒绝了工作流: " + summarize(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            // 连接失败、超时等网络问题
            throw new UpstreamException("ComfyUI 生成服务不可用，请稍后重试", e);
        }

        JsonNode json = mapper.readTree(response);
        JsonNode nodeErrors = json.path("node_errors");
        if (nodeErrors.isObject() && nodeErrors.size() > 0) {
            throw new UpstreamException("ComfyUI 拒绝了工作流: " + summarize(nodeErrors.toString()));
        }
        String promptId = json.path("prompt_id").asString("");
        if (promptId.isEmpty()) {
            throw new UpstreamException("ComfyUI 未返回 prompt_id: " + summarize(response));
        }
        log.debug("已提交 ComfyUI 任务 promptId={}", promptId);
        return promptId;
    }

    /**
     * 轮询 /history/{promptId}，直到任务出现在 history 里或超时。
     * ComfyUI 只在任务执行结束后才把它写进 history。
     */
    private JsonNode awaitCompletion(String promptId, long startedAtNanos) {
        long deadline = startedAtNanos + properties.generationTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode history = fetchHistory(promptId);
            JsonNode entry = history.get(promptId);
            if (entry != null) {
                String status = entry.path("status").path("status_str").asString("");
                if ("error".equals(status)) {
                    throw new UpstreamException("ComfyUI 执行工作流失败");
                }
                return entry;
            }
            sleepBeforeNextPoll();
        }
        throw new GenerationTimeoutException("生成超时，请稍后重试");
    }

    /** 查询一次任务历史。 */
    private JsonNode fetchHistory(String promptId) {
        String body;
        try {
            body = comfyUiRestClient.get()
                    .uri("/history/{promptId}", promptId)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new UpstreamException("ComfyUI 查询任务状态失败: " + summarize(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            throw new UpstreamException("ComfyUI 生成服务不可用，请稍后重试", e);
        }
        return mapper.readTree(body);
    }

    /**
     * 从 history 的 outputs 里找图片引用。
     * 优先取 type=output 的正式结果，找不到时退回第一个图片（例如预览图）。
     */
    private ImageReference findImage(JsonNode historyEntry) {
        ImageReference fallback = null;
        for (JsonNode output : historyEntry.path("outputs")) {
            for (JsonNode image : output.path("images")) {
                String filename = image.path("filename").asString("");
                if (filename.isEmpty()) {
                    continue;
                }
                ImageReference reference = new ImageReference(
                        filename,
                        image.path("subfolder").asString(""),
                        image.path("type").asString("output"));
                if ("output".equals(reference.type())) {
                    return reference;
                }
                if (fallback == null) {
                    fallback = reference;
                }
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new UpstreamException("ComfyUI 执行完成但没有返回图片");
    }

    /** 通过 /view 接口把图片字节下载回来。 */
    private byte[] download(ImageReference image) {
        try {
            byte[] bytes = comfyUiRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/view")
                            .queryParam("filename", image.filename())
                            .queryParam("subfolder", image.subfolder())
                            .queryParam("type", image.type())
                            .build())
                    .retrieve()
                    .body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw new UpstreamException("ComfyUI 返回了空图片");
            }
            return bytes;
        } catch (RestClientResponseException e) {
            throw new UpstreamException("ComfyUI 读取图片失败: " + summarize(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            throw new UpstreamException("ComfyUI 生成服务不可用，请稍后重试", e);
        }
    }

    /** 两次轮询之间短暂休眠；被中断时恢复中断标记并结束请求。 */
    private void sleepBeforeNextPoll() {
        try {
            Thread.sleep(properties.pollInterval().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("生成被中断", e);
        }
    }

    /** 把上游错误体压成一行并截断，方便写日志和返回给前端。 */
    private static String summarize(String value) {
        if (value == null || value.isBlank()) {
            return "无响应内容";
        }
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= MAX_ERROR_BODY_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_ERROR_BODY_LENGTH) + "...";
    }

    /** ComfyUI /view 需要的图片定位信息。 */
    private record ImageReference(String filename, String subfolder, String type) {
    }
}
