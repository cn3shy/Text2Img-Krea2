package io.github.cn3shy.text2img.service;

import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * 出图并发额度。
 *
 * <p>异步任务和旧的同步接口共用同一个实例，保证 ComfyUI 队列里最多有 4 个任务
 * （1 个执行 + 最多 3 个排队）。信号量是唯一的准入来源，任务表计数不参与准入。
 */
@Component
public class GenerationCapacity {

    private static final int MAX_CONCURRENT = 4;

    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT);

    /** 申请一个额度；没有空闲额度时立即返回 false。 */
    public boolean tryAcquire() {
        return semaphore.tryAcquire();
    }

    /** 归还额度；必须在 finally 中调用。 */
    public void release() {
        semaphore.release();
    }
}
