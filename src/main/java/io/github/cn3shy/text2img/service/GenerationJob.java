package io.github.cn3shy.text2img.service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 一次异步出图任务。
 *
 * <p>执行线程写、HTTP 线程读。所有可变字段都用 {@code volatile} 发布；写入顺序是
 * 先写内容字段、最后写 {@link #status()}，读取时先读状态再读其余字段。
 */
public final class GenerationJob {

    /** 任务状态。 */
    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    private final String id;
    private final int width;
    private final int height;
    private final LongSupplier clock;
    private final long submittedAtMillis;
    /** 真正开始执行的时刻；0 表示还没开始（仍在排队）。 */
    private final AtomicLong executedAtMillis = new AtomicLong();

    private volatile String promptId;
    private volatile Status status = Status.RUNNING;
    private volatile Integer progress;
    private volatile String stage = "排队中";
    private volatile byte[] image;
    private volatile String message;
    private volatile long finishedAtMillis;

    public GenerationJob(String id, int width, int height, LongSupplier clock) {
        this.id = id;
        this.width = width;
        this.height = height;
        this.clock = clock;
        this.submittedAtMillis = clock.getAsLong();
    }

    public String id() {
        return id;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public String promptId() {
        return promptId;
    }

    public Status status() {
        return status;
    }

    /** 0–100；尚未收到采样进度时为 {@code null}。 */
    public Integer progress() {
        return progress;
    }

    public String stage() {
        return stage;
    }

    public byte[] image() {
        return image;
    }

    public String message() {
        return message;
    }

    public long submittedAtMillis() {
        return submittedAtMillis;
    }

    public long executedAtMillis() {
        return executedAtMillis.get();
    }

    public long finishedAtMillis() {
        return finishedAtMillis;
    }

    public void setPromptId(String promptId) {
        this.promptId = promptId;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public void setProgress(Integer progress) {
        this.progress = progress;
    }

    /**
     * 标记「真正开始执行」。多个来源（WebSocket、/queue 轮询）可能同时调用，
     * 只接受第一次写入。
     */
    public boolean markExecutedNow() {
        return executedAtMillis.compareAndSet(0, clock.getAsLong());
    }

    /** 成功：先写图片等内容，最后写状态。 */
    public void succeed(byte[] image) {
        this.image = image;
        this.progress = 100;
        this.stage = "完成";
        this.finishedAtMillis = clock.getAsLong();
        this.status = Status.SUCCEEDED;
    }

    /** 失败：先写原因，最后写状态。 */
    public void fail(String message) {
        this.message = message;
        this.stage = null;
        this.finishedAtMillis = clock.getAsLong();
        this.status = Status.FAILED;
    }
}
