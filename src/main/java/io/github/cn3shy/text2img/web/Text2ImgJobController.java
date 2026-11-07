package io.github.cn3shy.text2img.web;

import io.github.cn3shy.text2img.service.GenerationJob;
import io.github.cn3shy.text2img.service.GenerationJobService;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步生图任务接口。
 *
 * <ul>
 *   <li>{@code POST /text2img/jobs}：提交任务，立即返回 202 + jobId；</li>
 *   <li>{@code GET /text2img/jobs/{jobId}}：查询状态、进度和阶段；</li>
 *   <li>{@code GET /text2img/jobs/{jobId}/image}：成功后取 PNG。</li>
 * </ul>
 */
@RestController
@RequestMapping("/text2img/jobs")
public class Text2ImgJobController {

    private static final long POLL_AFTER_MS = 1000;

    private final GenerationJobService service;

    public Text2ImgJobController(GenerationJobService service) {
        this.service = service;
    }

    /** 提交任务成功时的响应。 */
    public record JobCreatedResponse(String jobId, String status, long pollAfterMs) {
    }

    /** 任务状态响应；progress 在收到首个采样事件前为 null。 */
    public record JobStatusResponse(String jobId, String status, Integer progress,
                                    String stage, String message) {
    }

    @PostMapping
    public ResponseEntity<JobCreatedResponse> create(@RequestBody(required = false) Text2ImgRequest request) {
        GenerationJob job = service.submit(request);
        return ResponseEntity.accepted()
                .location(URI.create("/text2img/jobs/" + job.id()))
                .cacheControl(CacheControl.noStore())
                .body(new JobCreatedResponse(job.id(), statusOf(job), POLL_AFTER_MS));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobStatusResponse> status(@PathVariable String jobId) {
        GenerationJob job = service.get(jobId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new JobStatusResponse(job.id(), statusOf(job), job.progress(),
                        job.stage(), job.message()));
    }

    @GetMapping("/{jobId}/image")
    public ResponseEntity<byte[]> image(@PathVariable String jobId) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(service.getImage(jobId));
    }

    private static String statusOf(GenerationJob job) {
        return job.status().name().toLowerCase(Locale.ROOT);
    }
}
