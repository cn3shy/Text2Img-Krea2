package io.github.cn3shy.text2img.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GenerationCapacityTest {

    @Test
    void allowsFourConcurrentPermits() {
        GenerationCapacity capacity = new GenerationCapacity();

        for (int i = 0; i < 4; i++) {
            assertTrue(capacity.tryAcquire());
        }
        assertFalse(capacity.tryAcquire());

        capacity.release();
        assertTrue(capacity.tryAcquire());
    }
}
