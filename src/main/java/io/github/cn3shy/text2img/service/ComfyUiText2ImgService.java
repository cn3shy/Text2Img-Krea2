package io.github.cn3shy.text2img.service;

import io.github.cn3shy.text2img.config.ComfyUiProperties;
import io.github.cn3shy.text2img.error.GenerationTimeoutException;
import io.github.cn3shy.text2img.error.UpstreamException;
import io.github.cn3shy.text2img.web.Text2ImgRequest;
import io.github.cn3shy.text2img.workflow.WorkflowBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Synchronous ComfyUI driver: POST /prompt, poll /history/{id}, then download the
 * produced image from /view.
 */
public class ComfyUiText2ImgService implements Text2ImgService {

    private static final Logger log = LoggerFactory.getLogger(ComfyUiText2ImgService.class);
    private static final String CLIENT_ID = "text2img-backend";
    private static final int MAX_ERROR_BODY_LENGTH = 500;

    private final WorkflowBuilder workflowBuilder;
    private final ComfyUiProperties properties;
    private final ObjectMapper mapper;
    private final RestClient restClient;

    public ComfyUiText2ImgService(WorkflowBuilder workflowBuilder, ComfyUiProperties properties,
                                  ObjectMapper mapper, RestClient.Builder restClientBuilder) {
        this.workflowBuilder = workflowBuilder;
        this.properties = properties;
        this.mapper = mapper;
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties))
                .build();
    }

    @Override
    public byte[] generate(Text2ImgRequest request) {
        GenerationRequest generation = RequestValidator.validate(request);
        long startedAt = System.nanoTime();

        ObjectNode workflow = workflowBuilder.build(generation.prompt(), generation.width(), generation.height());
        String promptId = submit(workflow);
        JsonNode historyEntry = awaitCompletion(promptId, startedAt);
        ImageReference image = findImage(historyEntry);
        byte[] png = download(image);

        log.info("ComfyUI 生成完成 promptId={} size={}x{} bytes={} elapsedMs={}",
                promptId, generation.width(), generation.height(), png.length,
                (System.nanoTime() - startedAt) / 1_000_000);
        return png;
    }

    private String submit(ObjectNode workflow) {
        ObjectNode payload = mapper.createObjectNode();
        payload.set("prompt", workflow);
        payload.put("client_id", CLIENT_ID);
        String body = mapper.writeValueAsString(payload);

        String response;
        try {
            response = restClient.post()
                    .uri("/prompt")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new UpstreamException("ComfyUI 拒绝了工作流: " + summarize(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
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

    private JsonNode fetchHistory(String promptId) {
        String body;
        try {
            body = restClient.get()
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

    private byte[] download(ImageReference image) {
        try {
            byte[] bytes = restClient.get()
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

    private void sleepBeforeNextPoll() {
        try {
            Thread.sleep(properties.pollInterval().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("生成被中断", e);
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory(ComfyUiProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    private static String summarize(String value) {
        if (value == null || value.isBlank()) {
            return "无响应内容";
        }
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= MAX_ERROR_BODY_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_ERROR_BODY_LENGTH) + "...";
    }

    private record ImageReference(String filename, String subfolder, String type) {
    }
}
