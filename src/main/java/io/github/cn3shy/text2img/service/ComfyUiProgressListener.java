package io.github.cn3shy.text2img.service;

import io.github.cn3shy.text2img.config.ComfyUiProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 订阅 ComfyUI 的 WebSocket 进度事件。
 *
 * <p>每个任务单独连一次 {@code /ws?clientId={jobId}}，只处理属于本任务的事件。
 * 连接失败或中途断开都只影响进度展示，不影响出图（后台仍会轮询 /history）。
 * 不自动重连，任务结束时由调用方关闭。
 */
public final class ComfyUiProgressListener implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ComfyUiProgressListener.class);
    private static final String DEFAULT_PROGRESS_STAGE = "采样中";

    private final GenerationJob job;
    private final Map<String, String> nodeStages;
    private final ObjectMapper mapper;
    private volatile WebSocket socket;

    ComfyUiProgressListener(GenerationJob job, Map<String, String> nodeStages, ObjectMapper mapper) {
        this.job = job;
        this.nodeStages = nodeStages;
        this.mapper = mapper;
    }

    /** 异步连接进度通道；任何失败都不会抛给调用方。 */
    public static ComfyUiProgressListener connect(ComfyUiProperties properties, String clientId,
                                                  GenerationJob job, Map<String, String> nodeStages,
                                                  ObjectMapper mapper) {
        ComfyUiProgressListener listener = new ComfyUiProgressListener(job, nodeStages, mapper);
        listener.open(properties, clientId);
        return listener;
    }

    private void open(ComfyUiProperties properties, String clientId) {
        String wsUrl = properties.baseUrl().replaceFirst("^http", "ws") + "/ws?clientId=" + clientId;
        try {
            HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .buildAsync(URI.create(wsUrl), new Listener())
                    .whenComplete((webSocket, error) -> {
                        if (error != null) {
                            log.debug("连接 ComfyUI 进度 WebSocket 失败，本次任务没有实时进度", error);
                        }
                    });
        } catch (RuntimeException e) {
            log.debug("创建 ComfyUI 进度 WebSocket 失败，本次任务没有实时进度", e);
        }
    }

    /** 解析一条文本消息；包级可见，便于单测。 */
    void handleText(String text) {
        try {
            JsonNode root = mapper.readTree(text);
            JsonNode data = root.path("data");
            String eventPromptId = textOf(data.path("prompt_id"));
            if (!eventPromptId.isEmpty() && !eventPromptId.equals(job.promptId())) {
                return;
            }

            switch (textOf(root.path("type"))) {
                case "execution_start" -> {
                    job.markExecutedNow();
                    job.setStage("开始生成");
                }
                case "executing" -> {
                    // execution_start 可能早于连接建立，这里也认一次，只接受第一次写入
                    job.markExecutedNow();
                    String node = textOf(data.path("node"));
                    if (node.isEmpty()) {
                        job.setStage("收尾中");
                    } else {
                        String label = nodeStages.get(node);
                        if (label != null) {
                            job.setStage(label);
                        }
                    }
                }
                case "progress" -> {
                    int value = data.path("value").asInt();
                    int max = data.path("max").asInt();
                    if (max > 0) {
                        job.setProgress(Math.min(100, Math.round(value * 100f / max)));
                        String label = nodeStages.getOrDefault(textOf(data.path("node")),
                                DEFAULT_PROGRESS_STAGE);
                        job.setStage(label + " " + value + "/" + max);
                    }
                }
                default -> {
                    // 其他事件与出图进度无关
                }
            }
        } catch (RuntimeException e) {
            log.debug("无法解析 ComfyUI 进度消息: {}", text, e);
        }
    }

    /** JSON null / 缺失节点都按空字符串处理，避免拿到字面量 "null"。 */
    private static String textOf(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asString("");
    }

    @Override
    public void close() {
        WebSocket current = socket;
        if (current != null) {
            current.abort();
        }
    }

    private final class Listener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            handleText(data.toString());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            // ComfyUI 会推送预览图，忽略
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.debug("ComfyUI 进度 WebSocket 异常，本次任务没有实时进度", error);
        }
    }
}
