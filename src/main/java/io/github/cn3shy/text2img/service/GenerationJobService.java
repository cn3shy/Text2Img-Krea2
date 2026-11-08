package io.github.cn3shy.text2img.service;

import io.github.cn3shy.text2img.config.ComfyUiProperties;
import io.github.cn3shy.text2img.error.CapacityExceededException;
import io.github.cn3shy.text2img.error.GenerationTimeoutException;
import io.github.cn3shy.text2img.error.JobNotFoundException;
import io.github.cn3shy.text2img.error.JobNotReadyException;
import io.github.cn3shy.text2img.error.Text2ImgException;
import io.github.cn3shy.text2img.error.UpstreamException;
import io.github.cn3shy.text2img.web.Text2ImgRequest;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 异步出图任务服务。
 *
 * <p>提交时同步完成参数校验和 ComfyUI {@code /prompt} 提交（这两步都很快），
 * 然后把「轮询 history → 下载图片」交给虚拟线程执行；HTTP 线程立即返回任务。
 *
 * <p>超时分成两段：排队最多 {@code queue-timeout}，真正开始执行后最多
 * {@code generation-timeout}。排队时间不会被算成生成超时。
 */
@Service
public class GenerationJobService {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobService.class);

    private final ComfyUiText2ImgService comfyUi;
    private final ComfyUiProperties properties;
    private final GenerationJobStore store;
    private final ObjectMapper mapper;
    private final GenerationCapacity capacity;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public GenerationJobService(ComfyUiText2ImgService comfyUi, ComfyUiProperties properties,
                                GenerationJobStore store, ObjectMapper mapper,
                                GenerationCapacity capacity) {
        this.comfyUi = comfyUi;
        this.properties = properties;
        this.store = store;
        this.mapper = mapper;
        this.capacity = capacity;
    }

    /**
     * 校验并提交一个异步任务。
     *
     * @throws io.github.cn3shy.text2img.error.InvalidRequestException 参数不合法
     * @throws CapacityExceededException 并发额度已满
     * @throws UpstreamException ComfyUI 不可用或拒绝了工作流
     */
    public GenerationJob submit(Text2ImgRequest request) {
        GenerationRequest generation = RequestValidator.validate(request);
        if (!capacity.tryAcquire()) {
            throw new CapacityExceededException();
        }

        GenerationJob job;
        try {
            job = store.create(generation.width(), generation.height());
        } catch (RuntimeException e) {
            capacity.release();
            throw e;
        }
        log.info("生图任务受理 jobId={} size={}x{} prompt={}",
                job.id(), generation.width(), generation.height(), generation.prompt());

        ComfyUiProgressListener listener = null;
        try {
            PreparedWorkflow prepared = comfyUi.prepare(generation);
            listener = ComfyUiProgressListener.connect(properties, job.id(), job,
                    prepared.nodeStages(), mapper);
            String promptId = comfyUi.submit(prepared, job.id());
            job.setPromptId(promptId);
            log.info("已提交 ComfyUI 任务 jobId={} promptId={}", job.id(), promptId);

            ComfyUiProgressListener workerListener = listener;
            executor.execute(() -> run(job, workerListener));
            return job;
        } catch (RuntimeException e) {
            if (listener != null) {
                listener.close();
            }
            store.remove(job.id());
            capacity.release();
            throw e;
        }
    }

    public GenerationJob get(String jobId) {
        return store.find(jobId).orElseThrow(JobNotFoundException::new);
    }

    public byte[] getImage(String jobId) {
        GenerationJob job = get(jobId);
        if (job.status() != GenerationJob.Status.SUCCEEDED || job.image() == null) {
            throw new JobNotReadyException();
        }
        return job.image();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private void run(GenerationJob job, ComfyUiProgressListener listener) {
        try {
            String promptId = job.promptId();
            long queueDeadline = job.submittedAtMillis() + properties.queueTimeout().toMillis();

            while (true) {
                JsonNode history = comfyUi.fetchHistory(promptId);
                JsonNode entry = history.get(promptId);
                if (entry != null) {
                    if ("error".equals(entry.path("status").path("status_str").asString(""))) {
                        throw new UpstreamException("ComfyUI 执行工作流失败");
                    }
                    byte[] png = comfyUi.downloadImage(entry);
                    job.succeed(png);
                    log.info("ComfyUI 生成完成 jobId={} promptId={} bytes={} elapsedMs={}",
                            job.id(), promptId, png.length,
                            System.currentTimeMillis() - job.submittedAtMillis());
                    return;
                }

                if (job.executedAtMillis() == 0 && comfyUi.isRunning(promptId)) {
                    if (job.markExecutedNow()) {
                        log.info("生图任务开始执行 jobId={} promptId={} queueMs={}",
                                job.id(), promptId, job.executedAtMillis() - job.submittedAtMillis());
                    }
                }

                long now = System.currentTimeMillis();
                if (job.executedAtMillis() == 0) {
                    if (now > queueDeadline) {
                        throw new GenerationTimeoutException("排队超时，请稍后重试");
                    }
                } else if (now > job.executedAtMillis() + properties.generationTimeout().toMillis()) {
                    throw new GenerationTimeoutException("生成超时，请稍后重试");
                }

                sleepBeforeNextPoll();
            }
        } catch (Text2ImgException e) {
            log.warn("异步生图失败 jobId={} stage={} progress={} message={}",
                    job.id(), job.stage(), job.progress(), e.getMessage());
            job.fail(e.getMessage());
        } catch (RuntimeException e) {
            log.error("异步生图异常 jobId={} stage={} progress={}",
                    job.id(), job.stage(), job.progress(), e);
            job.fail("服务器内部错误，请稍后重试");
        } finally {
            listener.close();
            capacity.release();
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
}
