package io.github.cn3shy.text2img.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GenerationJobStoreTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final GenerationJobStore store = new GenerationJobStore(now::get);

    @Test
    void finishedJobIsPurgedAfterFiveMinutes() {
        GenerationJob job = store.create(1024, 1024);
        job.succeed(new byte[] {1, 2, 3});

        now.addAndGet(Duration.ofMinutes(4).toMillis());
        store.create(1024, 1024);
        assertTrue(store.find(job.id()).isPresent());

        now.addAndGet(Duration.ofMinutes(2).toMillis());
        store.create(1024, 1024);
        assertTrue(store.find(job.id()).isEmpty());
    }

    @Test
    void evictsOldestFinishedJobWhenRetentionIsFull() {
        GenerationJob oldest = store.create(1024, 1024);
        oldest.succeed(new byte[] {1});
        for (int i = 0; i < 15; i++) {
            store.create(1024, 1024);
        }

        GenerationJob newest = store.create(1024, 1024);

        assertTrue(store.find(oldest.id()).isEmpty());
        assertTrue(store.find(newest.id()).isPresent());
    }
}
