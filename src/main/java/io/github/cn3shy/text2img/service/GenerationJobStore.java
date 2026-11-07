package io.github.cn3shy.text2img.service;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * 进程内的生图任务表。
 *
 * <p>单实例部署，任务和图片字节都放在内存里；进程重启后任务全部丢失。
 * 已结束的任务保留 5 分钟，最多保留 {@value #MAX_RETAINED} 个（含进行中）。
 * 真正的并发准入由 {@link GenerationCapacity} 负责，这里只负责保存与过期清理。
 */
@Component
public class GenerationJobStore {

    static final int MAX_RETAINED = 16;
    static final Duration RETENTION = Duration.ofMinutes(5);

    private final Map<String, GenerationJob> jobs = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    /** Spring 使用的构造器。 */
    public GenerationJobStore() {
        this(System::currentTimeMillis);
    }

    /** 测试用：注入可控时钟。 */
    GenerationJobStore(LongSupplier clock) {
        this.clock = clock;
    }

    public GenerationJob create(int width, int height) {
        purgeExpired();
        evictOldestFinishedIfFull();
        GenerationJob job = new GenerationJob(UUID.randomUUID().toString(), width, height, clock);
        jobs.put(job.id(), job);
        return job;
    }

    public Optional<GenerationJob> find(String id) {
        purgeExpired();
        return Optional.ofNullable(jobs.get(id));
    }

    public void remove(String id) {
        jobs.remove(id);
    }

    private void purgeExpired() {
        long threshold = clock.getAsLong() - RETENTION.toMillis();
        jobs.values().removeIf(job ->
                job.finishedAtMillis() > 0 && job.finishedAtMillis() < threshold);
    }

    private void evictOldestFinishedIfFull() {
        if (jobs.size() < MAX_RETAINED) {
            return;
        }
        jobs.values().stream()
                .filter(job -> job.status() != GenerationJob.Status.RUNNING)
                .min(Comparator.comparingLong(GenerationJob::finishedAtMillis))
                .ifPresent(job -> jobs.remove(job.id()));
    }
}
