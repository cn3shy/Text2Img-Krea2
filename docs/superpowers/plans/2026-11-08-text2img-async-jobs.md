# text2img 异步任务 + 进度条 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把同步出图接口改造成「提交任务 → 轮询状态 → 取图」的异步流程，加入真实采样进度，并消除客户端断开时的误导性 500 日志；同时让 Vercel 代理不受 60 秒和 4.5MB 限制。

**Architecture:** 后端新增内存任务表、共享 `Semaphore(4)` 准入、虚拟线程执行器；每个任务提交 ComfyUI 后由后台线程轮询 `/history`、用 `/queue` 判定执行起点、用 WebSocket 收进度。前端每秒轮询状态接口并平滑展示进度。Vercel 用单函数 `api/index.ts` 统一流式代理 `/api/*`。

**Tech Stack:** Java 25 / Spring Boot 4.1.2 / Jackson 3（`tools.jackson`）/ JUnit 5 + Mockito；Vue 3 + TypeScript + Vite 5 / pnpm；Vercel Functions（Node 22）。

**Spec:** `D:\Project\Blog\text2img\docs\superpowers\specs\2026-11-08-text2img-async-jobs-design.md`

## Global Constraints

- 仓库边界：后端仓库 `D:\Project\Blog\text2img`（独立 git），前端仓库 `D:\Project\Blog`；前端仓库已用 `.gitignore` 排除 `text2img/`，**两个仓库分别 add/commit**。
- 后端不新增任何第三方依赖；WebSocket 用 JDK 自带 `java.net.http.WebSocket`。
- 后端保持 Java 25、Spring Boot 4.1.2、Jackson 3（`tools.jackson.databind.*`，注意用 `asString()` 而不是 `asText()`）。
- 构建命令：
  - `$env:JAVA_HOME="C:\DEV\jdk\jdk25"; & "C:\DEV\Soft\IDEA\plugins\maven\lib\maven3\bin\mvn.cmd" -f D:\Project\Blog\text2img\pom.xml test`
  - 前端类型检查：`cd D:\Project\Blog; pnpm exec vue-tsc --noEmit`
- 并发与超时：`Semaphore(4)`；`queue-timeout` 默认 5 分钟；`generation-timeout` 默认 100 秒（从 `executedAt` 起算）；任务结束保留 5 分钟，最多保留 16 个。
- HTTP 状态：提交成功 202；参数错 400；容量满/上游错 503；任务不存在 404；图片未就绪/失败 409。
- 前端：轮询间隔默认 1000ms；轮询失败重试 3 次 × 1 秒；404 终止；整体安全上限 10 分钟。
- 所有面向用户的中文文案必须与设计文档逐字一致（如「排队超时，请稍后重试」「当前生成任务较多，请稍后再试」「任务不存在或已过期」「图片还没准备好」）。
- 旧的同步 `POST /text2img` 行为保持不变，但必须共享同一个 `Semaphore(4)`。

## Review Focus

1. **第 5 个并发提交**：前 4 个应正常进入排队，第 5 个必须返回 503，不能出现第 5 个抢占或前 4 个被误杀（Task 4 测试）。
2. **排队中的任务**：排队 65–200 秒只应计入排队时间，不能被当成「生成超时」（Task 4 测试）。
3. **用户取消 / 关闭页面**：后端只记一条 WARN，不尝试写 500 JSON、不打印整段 `ClientAbortException` 堆栈（Task 5 测试）。
4. **同时使用 ComfyUI 网页出图**：其他 `prompt_id` 的 `progress` / `executing` 事件不能污染本任务的进度和阶段（Task 2 测试）。
5. **2048×2048 的 PNG 经 Vercel 代理**：必须流式返回，不能撞 4.5MB 的 `FUNCTION_RESPONSE_PAYLOAD_TOO_LARGE`（Task 8 实现 + 线上验收）。

---

### Task 1: 任务模型与内存任务表

**Files:**
- Create: `text2img/src/main/java/io/github/cn3shy/text2img/service/GenerationJob.java`
- Create: `text2img/src/main/java/io/github/cn3shy/text2img/service/GenerationJobStore.java`
- Test: `text2img/src/test/java/io/github/cn3shy/text2img/service/GenerationJobStoreTest.java`

**Interfaces:**
- Consumes: 无。
- Produces:
  - `GenerationJob(String id, int width, int height, LongSupplier clock)`
  - `enum GenerationJob.Status { RUNNING, SUCCEEDED, FAILED }`
  - `id() -> String`、`width() -> int`、`height() -> int`、`status() -> Status`、`progress() -> Integer`（可空）、`stage() -> String`（可空）、`image() -> byte[]`（可空）、`message() -> String`（可空）、`promptId() -> String`（可空）
  - `submittedAtMillis() -> long`、`executedAtMillis() -> long`（0 表示尚未开始执行）、`finishedAtMillis() -> long`（0 表示未结束）
  - `setPromptId(String)`、`setStage(String)`、`setProgress(Integer)`、`markExecutedNow() -> boolean`（CAS 0→now，返回是否首次写入）、`succeed(byte[])`、`fail(String)`
  - `GenerationJobStore()`（Spring 用）、`GenerationJobStore(LongSupplier clock)`（测试用）
  - `create(int width, int height) -> GenerationJob`、`find(String id) -> Optional<GenerationJob>`、`remove(String id)`

**实现要点：** 所有可变字段 `volatile`；`succeed`/`fail` 先写内容字段、最后写 `status`；`markExecutedNow` 用 `AtomicLong.compareAndSet(0, clock.getAsLong())`。任务表在 `create`/`find` 时清理「已结束且 `finishedAtMillis` 超过 5 分钟」的任务；`size() >= 16` 时淘汰最老的已结束任务，没有已结束任务时允许插入（同时最多 4 个在跑，不会无限增长）。

- [ ] **Step 1: 写失败测试**

```java
package io.github.cn3shy.text2img.service;

import static org.junit.jupiter.api.Assertions.*;

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
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `$env:JAVA_HOME="C:\DEV\jdk\jdk25"; & "C:\DEV\Soft\IDEA\plugins\maven\lib\maven3\bin\mvn.cmd" -f D:\Project\Blog\text2img\pom.xml test -Dtest=GenerationJobStoreTest`
Expected: 编译失败，提示找不到 `GenerationJob` / `GenerationJobStore`。

- [ ] **Step 3: 实现 `GenerationJob` 与 `GenerationJobStore`**

按上面的 Interfaces 实现；不要额外增加测试钩子。`GenerationJobStore` 的包级构造器用于注入时钟，公开无参构造器交给 Spring。

- [ ] **Step 4: 运行测试，确认通过**

Run: 同 Step 2
Expected: `BUILD SUCCESS`，2 个测试通过。

- [ ] **Step 5: 提交（后端仓库）**

```bash
cd D:/Project/Blog/text2img
git add src/main/java/io/github/cn3shy/text2img/service/GenerationJob.java src/main/java/io/github/cn3shy/text2img/service/GenerationJobStore.java src/test/java/io/github/cn3shy/text2img/service/GenerationJobStoreTest.java
git commit -m "feat: 增加生图任务模型与内存任务表"
```

---

### Task 2: ComfyUI 进度消息解析

**Files:**
- Create: `text2img/src/main/java/io/github/cn3shy/text2img/service/ComfyUiProgressListener.java`
- Test: `text2img/src/test/java/io/github/cn3shy/text2img/service/ComfyUiProgressListenerTest.java`

**Interfaces:**
- Consumes: `GenerationJob`（Task 1）。
- Produces:
  - `static ComfyUiProgressListener connect(ComfyUiProperties properties, String clientId, GenerationJob job, Map<String, String> nodeStages, ObjectMapper mapper)` — 永不抛异常；连接失败只记 debug。
  - `ComfyUiProgressListener(GenerationJob job, Map<String, String> nodeStages, ObjectMapper mapper)`（包级，供测试）
  - `void handleText(String text)`（包级，供测试）
  - `void close()`（实现 `AutoCloseable`）

**实现要点：**
- URL：`properties.baseUrl().replaceFirst("^http", "ws") + "/ws?clientId=" + clientId`。
- `HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(2)).buildAsync(uri, listener)`；`onOpen` 和每条消息后都 `webSocket.request(1)`。
- `handleText`：解析 `type`/`data`；带 `prompt_id` 且不等于 `job.promptId()` 时直接返回。
  - `execution_start` → `job.markExecutedNow()`；`job.setStage("开始生成")`。
  - `executing` → `data.node` 为空则 `job.setStage("收尾中")`；否则 `nodeStages.get(node)` 非空才更新阶段。
  - `progress` → `max > 0` 时 `percent = Math.min(100, Math.round(value * 100f / max))`；`job.setProgress(percent)`；`stage = nodeStages.getOrDefault(node, "采样中") + " " + value + "/" + max`。
  - 其他 `type` 忽略。
- `close()` 调 `webSocket.abort()`（持有 `volatile WebSocket`，未连上时为空操作）。

- [ ] **Step 1: 写失败测试**

```java
package io.github.cn3shy.text2img.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ComfyUiProgressListenerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GenerationJob job = new GenerationJob("job-1", 1024, 1024, System::currentTimeMillis);

    private ComfyUiProgressListener listener() {
        job.setPromptId("prompt-1");
        return new ComfyUiProgressListener(job, Map.of("70", "采样中", "75", "解码图像"), mapper);
    }

    @Test
    void progressUpdatesPercentAndStage() {
        ComfyUiProgressListener listener = listener();

        listener.handleText(
                "{\"type\":\"progress\",\"data\":{\"value\":3,\"max\":8,\"prompt_id\":\"prompt-1\",\"node\":\"70\"}}");

        assertEquals(38, job.progress());
        assertEquals("采样中 3/8", job.stage());
    }

    @Test
    void ignoresProgressForOtherPrompt() {
        ComfyUiProgressListener listener = listener();

        listener.handleText(
                "{\"type\":\"progress\",\"data\":{\"value\":3,\"max\":8,\"prompt_id\":\"other\",\"node\":\"70\"}}");

        assertNull(job.progress());
    }

    @Test
    void executingUpdatesStageAndMarksExecutionStart() {
        ComfyUiProgressListener listener = listener();

        listener.handleText("{\"type\":\"executing\",\"data\":{\"node\":\"75\",\"prompt_id\":\"prompt-1\"}}");

        assertEquals("解码图像", job.stage());
        assertTrue(job.executedAtMillis() > 0);
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn test -Dtest=ComfyUiProgressListenerTest`（完整命令同 Task 1 Step 2）
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现 `ComfyUiProgressListener`**

按 Interfaces 和实现要点编写；WebSocket 连接与 JSON 解析分开，解析逻辑只在 `handleText` 中。

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2
Expected: 3 个测试通过。

- [ ] **Step 5: 提交（后端仓库）**

```bash
cd D:/Project/Blog/text2img
git add src/main/java/io/github/cn3shy/text2img/service/ComfyUiProgressListener.java src/test/java/io/github/cn3shy/text2img/service/ComfyUiProgressListenerTest.java
git commit -m "feat: 接入 ComfyUI WebSocket 进度事件"
```

---

### Task 3: ComfyUI 客户端原语与阶段映射

**Files:**
- Modify: `text2img/src/main/java/io/github/cn3shy/text2img/service/ComfyUiText2ImgService.java`
- Create: `text2img/src/main/java/io/github/cn3shy/text2img/service/PreparedWorkflow.java`
- Modify: `text2img/src/main/java/io/github/cn3shy/text2img/config/ComfyUiProperties.java`
- Modify: `text2img/src/main/resources/application.yaml`
- Test: `text2img/src/test/java/io/github/cn3shy/text2img/service/ComfyUiText2ImgServiceTest.java`

**Interfaces:**
- Consumes: 现有 `WorkflowBuilder`、`ComfyUiProperties`、`RestClient`。
- Produces:
  - `ComfyUiProperties(String baseUrl, Duration connectTimeout, Duration readTimeout, Duration generationTimeout, Duration queueTimeout, Duration pollInterval, String workflow)`；`queueTimeout()` 默认 `5m`（新增 `comfyui.queue-timeout` / `COMFYUI_QUEUE_TIMEOUT`）。
  - `public record PreparedWorkflow(ObjectNode workflow, Map<String, String> nodeStages)`
  - `prepare(GenerationRequest generation) -> PreparedWorkflow`
  - `submit(PreparedWorkflow prepared, String clientId) -> String`（返回 `prompt_id`，沿用现有 `POST /prompt` 逻辑）
  - `fetchHistory(String promptId) -> JsonNode`（由 private 改为 public）
  - `downloadImage(JsonNode historyEntry) -> byte[]`（= 现有 `findImage` + `download`）
  - `isRunning(String promptId) -> boolean`（`GET /queue`，检查 `queue_running`；请求失败返回 false）
  - `static boolean isPromptRunning(JsonNode queue, String promptId)`（包级，供测试）

**实现要点：**
- `prepare` 调 `workflowBuilder.build(...)`，然后遍历 `workflow.properties()` 生成节点 id → 阶段文案：`class_type` 以 `KSampler` 开头 → 「采样中」；以 `VAEDecode` 开头 → 「解码图像」；等于 `SaveImage` → 「保存图像」；以 `Loader` 结尾 → 「加载模型」；等于 `CLIPTextEncode` → 「编码提示词」；其他不加入 map。
- `submit(PreparedWorkflow, String clientId)` 把请求体的 `client_id` 改为传入值；同步 `generate` 继续传常量 `CLIENT_ID`。
- `generate(Text2ImgRequest)` 改为 `prepare` + `submit` + 现有等待逻辑，行为和日志不变。
- `/queue` 条目是数组，`prompt_id` 在下标 1：`item.path(1).asString("")`。
- `ComfyUiProperties` 增加 `queueTimeout` 组件与默认值 `5m`，`application.yaml` 在
  `generation-timeout` 后加 `queue-timeout: ${COMFYUI_QUEUE_TIMEOUT:5m}`。

- [ ] **Step 1: 写失败测试**

```java
package io.github.cn3shy.text2img.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.cn3shy.text2img.workflow.WorkflowBuilder;

class ComfyUiText2ImgServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ComfyUiProperties properties = new ComfyUiProperties("http://127.0.0.1:8188",
            Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofSeconds(100),
            Duration.ofMinutes(5), Duration.ofMillis(500), "classpath:Krea2-t2i.json");
    private final ComfyUiText2ImgService service = new ComfyUiText2ImgService(
            new WorkflowBuilder(mapper, properties, new DefaultResourceLoader()),
            properties, mapper, mock(RestClient.class));

    @Test
    void prepareMapsNodesToStageLabels() {
        PreparedWorkflow prepared = service.prepare(new GenerationRequest("cat", 1024, 1024));

        assertEquals("采样中", prepared.nodeStages().get("70"));
        assertEquals("解码图像", prepared.nodeStages().get("75"));
        assertEquals("保存图像", prepared.nodeStages().get("9"));
    }

    @Test
    void detectsPromptInQueueRunning() {
        JsonNode queue = mapper.readTree(
                "{\"queue_running\":[[0,\"p1\",{},{}]],\"queue_pending\":[[1,\"p2\",{},{}]]}");

        assertTrue(ComfyUiText2ImgService.isPromptRunning(queue, "p1"));
        assertFalse(ComfyUiText2ImgService.isPromptRunning(queue, "p2"));
        assertFalse(ComfyUiText2ImgService.isPromptRunning(queue, "p3"));
    }

    @Test
    void queueTimeoutDefaultsToFiveMinutes() {
        ComfyUiProperties defaults = new ComfyUiProperties(null, null, null, null, null, null, null);

        assertEquals(Duration.ofMinutes(5), defaults.queueTimeout());
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn test -Dtest=ComfyUiText2ImgServiceTest`
Expected: 编译失败（`prepare` / `isPromptRunning` / 新构造参数不存在）。

- [ ] **Step 3: 实现原语与阶段映射**

按 Interfaces 改造 `ComfyUiText2ImgService`，新增 `PreparedWorkflow`；不要改变同步 `generate` 的外部行为。

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2
Expected: 3 个测试通过。

- [ ] **Step 5: 提交（后端仓库）**

```bash
cd D:/Project/Blog/text2img
git add src/main/java/io/github/cn3shy/text2img/service/ComfyUiText2ImgService.java src/main/java/io/github/cn3shy/text2img/service/PreparedWorkflow.java src/main/java/io/github/cn3shy/text2img/config/ComfyUiProperties.java src/main/resources/application.yaml src/test/java/io/github/cn3shy/text2img/service/ComfyUiText2ImgServiceTest.java
git commit -m "refactor: 暴露 ComfyUI 提交与队列查询原语，增加排队超时配置"
```

---

### Task 4: 任务服务、控制器、错误与配置

**Files:**
- Create: `text2img/src/main/java/io/github/cn3shy/text2img/service/GenerationJobService.java`
- Create: `text2img/src/main/java/io/github/cn3shy/text2img/service/GenerationCapacity.java`
- Create: `text2img/src/main/java/io/github/cn3shy/text2img/web/Text2ImgJobController.java`
- Create: `text2img/src/main/java/io/github/cn3shy/text2img/error/JobNotFoundException.java`
- Create: `text2img/src/main/java/io/github/cn3shy/text2img/error/JobNotReadyException.java`
- Create: `text2img/src/main/java/io/github/cn3shy/text2img/error/CapacityExceededException.java`
- Modify: `text2img/src/main/java/io/github/cn3shy/text2img/web/Text2ImgController.java`
- Test: `text2img/src/test/java/io/github/cn3shy/text2img/service/GenerationCapacityTest.java`
- Test: `text2img/src/test/java/io/github/cn3shy/text2img/service/GenerationJobServiceTest.java`
- Test: `text2img/src/test/java/io/github/cn3shy/text2img/web/Text2ImgControllerTest.java`
- Test: `text2img/src/test/java/io/github/cn3shy/text2img/web/Text2ImgJobControllerTest.java`

**Interfaces:**
- Consumes: Task 1–3 的 `GenerationJob`、`GenerationJobStore`、`ComfyUiProgressListener`、`ComfyUiText2ImgService.prepare/submit/fetchHistory/downloadImage/isRunning`、`PreparedWorkflow`。
- Produces:
  - `ComfyUiProperties(..., Duration generationTimeout, Duration queueTimeout, Duration pollInterval, String workflow)`；`queueTimeout()` 默认 `5m`。
  - `@Component GenerationCapacity`：`boolean tryAcquire()`、`void release()`，内部 `Semaphore(4)`；**异步和同步两条路径共用同一个 Bean**。
  - `@Service GenerationJobService`，构造器 `(ComfyUiText2ImgService, ComfyUiProperties, GenerationJobStore, ObjectMapper, GenerationCapacity)`：
    - `GenerationJob submit(Text2ImgRequest request)`（参数错 → `InvalidRequestException`；无许可 → `CapacityExceededException`）
    - `GenerationJob get(String jobId)`（不存在 → `JobNotFoundException`）
    - `byte[] getImage(String jobId)`（不存在 → `JobNotFoundException`；未成功 → `JobNotReadyException`）
    - `@PreDestroy void shutdown()`
  - `Text2ImgController` 注入 `(Text2ImgService, GenerationCapacity)`；`text2img()` 先 `tryAcquire()`，失败抛 `CapacityExceededException`，`finally` 释放。
  - `@RestController @RequestMapping("/text2img/jobs") Text2ImgJobController`，构造器 `(GenerationJobService)`，方法 `create` / `status` / `image`：
    - `create(Text2ImgRequest) -> ResponseEntity<JobCreatedResponse>`：202 + `Location: /text2img/jobs/{id}` + 嵌套 public record `JobCreatedResponse(String jobId, String status, long pollAfterMs=1000)`
    - `status(String jobId) -> ResponseEntity<JobStatusResponse>`：嵌套 public record `JobStatusResponse(String jobId, String status, Integer progress, String stage, String message)`
    - `image(String jobId) -> ResponseEntity<byte[]>`：`image/png`
  - 三个异常都继承 `Text2ImgException`：404「任务不存在或已过期」、409「图片还没准备好」、503「当前生成任务较多，请稍后再试」。

**实现要点：**
- `GenerationCapacity` 是唯一许可来源（`Semaphore(4)`）；`GenerationJobService` 与 `Text2ImgController` 都注入它，谁都不能绕过。
- `GenerationJobService` 内部创建 `Executors.newVirtualThreadPerTaskExecutor()`，`@PreDestroy` 关闭。
- 控制器测试**不使用 `@WebMvcTest`**：Spring Boot 4 把 Web MVC 测试切片拆到了 `spring-boot-starter-webmvc-test`，本项目未引入；直接 new 控制器 + Mockito mock 服务即可，JSON 序列化由端到端验收覆盖。
- 提交顺序：`RequestValidator.validate` → `tryAcquire()` → `store.create` → `prepare` → `connect` 进度监听 → `submit(prepared, job.id())` → `job.setPromptId` → 起后台线程 → 返回。
- 任何提交阶段异常：`store.remove`、`capacity.release()`、原样抛出。
- 后台线程按设计 5.3 的两段计时循环：先查 history；没有则查 `isRunning` 设置 `executedAt`；再判断排队/执行超时；最后 sleep `pollInterval`。成功 `job.succeed(png)`；失败 `job.fail(...)`；`finally` 关闭监听并 `capacity.release()`。
- 控制器状态字符串用 `job.status().name().toLowerCase(Locale.ROOT)`；三个响应都加 `Cache-Control: no-store`。
- `queue-timeout` 配置已在 Task 3 加入，本任务直接使用 `properties.queueTimeout()`。

- [ ] **Step 1: 写失败测试（服务层）**

```java
package io.github.cn3shy.text2img.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import io.github.cn3shy.text2img.error.CapacityExceededException;
import io.github.cn3shy.text2img.web.Text2ImgRequest;

class GenerationJobServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ComfyUiText2ImgService comfyUi = mock(ComfyUiText2ImgService.class);
    private final CountDownLatch block = new CountDownLatch(1);
    private GenerationJobService service;

    private GenerationJobService service(Duration queueTimeout, boolean blockHistory) {
        ComfyUiProperties properties = new ComfyUiProperties("http://127.0.0.1:1",
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(30),
                queueTimeout, Duration.ofMillis(10), "classpath:Krea2-t2i.json");
        when(comfyUi.prepare(any())).thenReturn(new PreparedWorkflow(mapper.createObjectNode(), Map.of()));
        when(comfyUi.submit(any(), anyString())).thenReturn("prompt-1");
        when(comfyUi.isRunning(anyString())).thenReturn(false);
        when(comfyUi.fetchHistory(anyString())).thenAnswer(invocation -> {
            if (blockHistory) {
                block.await();
            }
            return mapper.createObjectNode();
        });
        service = new GenerationJobService(comfyUi, properties, new GenerationJobStore(), mapper,
                new GenerationCapacity());
        return service;
    }

    @AfterEach
    void tearDown() {
        block.countDown();
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void fifthConcurrentSubmitIsRejected() throws Exception {
        service(Duration.ofMillis(100), true);
        for (int i = 0; i < 4; i++) {
            service.submit(new Text2ImgRequest("cat", 1024, 1024));
        }

        assertThrows(CapacityExceededException.class,
                () -> service.submit(new Text2ImgRequest("cat", 1024, 1024)));
    }

    @Test
    void queuedJobFailsWithQueueTimeoutMessage() throws Exception {
        service(Duration.ofMillis(50), false);
        GenerationJob job = service.submit(new Text2ImgRequest("cat", 1024, 1024));

        long deadline = System.currentTimeMillis() + 5_000;
        while (job.status() == GenerationJob.Status.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(GenerationJob.Status.FAILED, job.status());
        assertEquals("排队超时，请稍后重试", job.message());
    }
}
```

- [ ] **Step 2: 写失败测试（控制器与并发许可）**

```java
package io.github.cn3shy.text2img.service;

import static org.junit.jupiter.api.Assertions.*;

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
```

```java
package io.github.cn3shy.text2img.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import io.github.cn3shy.text2img.error.CapacityExceededException;
import io.github.cn3shy.text2img.service.GenerationCapacity;
import io.github.cn3shy.text2img.service.Text2ImgService;

class Text2ImgControllerTest {

    private final Text2ImgService service = mock(Text2ImgService.class);
    private final GenerationCapacity capacity = mock(GenerationCapacity.class);
    private final Text2ImgController controller = new Text2ImgController(service, capacity);

    @Test
    void rejectsWhenCapacityIsExhausted() {
        when(capacity.tryAcquire()).thenReturn(false);

        assertThrows(CapacityExceededException.class,
                () -> controller.text2img(new Text2ImgRequest("cat", 1024, 1024)));
        verifyNoInteractions(service);
    }

    @Test
    void releasesCapacityEvenWhenGenerationFails() {
        when(capacity.tryAcquire()).thenReturn(true);
        when(service.generate(any())).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class,
                () -> controller.text2img(new Text2ImgRequest("cat", 1024, 1024)));

        verify(capacity).release();
    }
}
```

```java
package io.github.cn3shy.text2img.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import io.github.cn3shy.text2img.error.CapacityExceededException;
import io.github.cn3shy.text2img.error.JobNotFoundException;
import io.github.cn3shy.text2img.error.JobNotReadyException;
import io.github.cn3shy.text2img.service.GenerationJob;
import io.github.cn3shy.text2img.service.GenerationJobService;

class Text2ImgJobControllerTest {

    private final GenerationJobService service = mock(GenerationJobService.class);
    private final Text2ImgJobController controller = new Text2ImgJobController(service);

    @Test
    void createReturns202WithJobIdAndLocation() {
        when(service.submit(any())).thenReturn(new GenerationJob("job-1", 1024, 1024, System::currentTimeMillis));

        ResponseEntity<Text2ImgJobController.JobCreatedResponse> response =
                controller.create(new Text2ImgRequest("cat", 1024, 1024));

        assertEquals(202, response.getStatusCode().value());
        assertEquals("/text2img/jobs/job-1", response.getHeaders().getLocation().toString());
        assertEquals("job-1", response.getBody().jobId());
        assertEquals("running", response.getBody().status());
        assertEquals(1000, response.getBody().pollAfterMs());
    }

    @Test
    void unknownJobThrowsNotFound() {
        when(service.get("missing")).thenThrow(new JobNotFoundException());

        assertThrows(JobNotFoundException.class, () -> controller.status("missing"));
    }

    @Test
    void imageNotReadyThrowsConflict() {
        when(service.getImage("job-1")).thenThrow(new JobNotReadyException());

        assertThrows(JobNotReadyException.class, () -> controller.image("job-1"));
    }

    @Test
    void imageReturnsPngBytes() {
        when(service.getImage("job-1")).thenReturn(new byte[] {1, 2, 3});

        ResponseEntity<byte[]> response = controller.image("job-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertArrayEquals(new byte[] {1, 2, 3}, response.getBody());
    }

    @Test
    void capacityExceededPropagates() {
        when(service.submit(any())).thenThrow(new CapacityExceededException());

        assertThrows(CapacityExceededException.class,
                () -> controller.create(new Text2ImgRequest("cat", 1024, 1024)));
    }
}
```

- [ ] **Step 3: 运行，确认失败**

Run: `mvn test -Dtest=GenerationCapacityTest,GenerationJobServiceTest,Text2ImgControllerTest,Text2ImgJobControllerTest`
Expected: 编译失败（服务/控制器/异常/许可类不存在；`ComfyUiProperties` 不匹配）。

- [ ] **Step 4: 实现异常、许可、配置、服务与控制器**

按 Interfaces 和实现要点实现。注意：
- `GenerationJobService` 只保留一个公开构造器 `(ComfyUiText2ImgService, ComfyUiProperties, GenerationJobStore, ObjectMapper)`，执行器在内部创建。
- `submit` 中如果 `store.create` 抛异常，必须归还许可。
- 后台线程的 `catch (Text2ImgException e)` 用 `e.getMessage()`，`catch (RuntimeException e)` 统一「服务器内部错误，请稍后重试」并记录 `log.error`。
- 控制器不要声明 `produces`，图片接口显式 `contentType(MediaType.IMAGE_PNG)`。

- [ ] **Step 5: 运行，确认四个测试类全部通过**

Run: 同 Step 3
Expected: 10 个测试通过（许可 1 + 服务 2 + 同步控制器 2 + 任务控制器 5）。

- [ ] **Step 6: 提交（后端仓库）**

```bash
cd D:/Project/Blog/text2img
git add src/main/java/io/github/cn3shy/text2img/service/GenerationCapacity.java src/main/java/io/github/cn3shy/text2img/service/GenerationJobService.java src/main/java/io/github/cn3shy/text2img/web/Text2ImgController.java src/main/java/io/github/cn3shy/text2img/web/Text2ImgJobController.java src/main/java/io/github/cn3shy/text2img/error/ src/test/java/io/github/cn3shy/text2img/service/GenerationCapacityTest.java src/test/java/io/github/cn3shy/text2img/service/GenerationJobServiceTest.java src/test/java/io/github/cn3shy/text2img/web/Text2ImgControllerTest.java src/test/java/io/github/cn3shy/text2img/web/Text2ImgJobControllerTest.java
git commit -m "feat: 增加异步生图任务接口与并发准入"
```

---

### Task 5: 客户端断开不再按 500 处理

**Files:**
- Modify: `text2img/src/main/java/io/github/cn3shy/text2img/web/ApiExceptionHandler.java`
- Test: `text2img/src/test/java/io/github/cn3shy/text2img/web/ApiExceptionHandlerTest.java`

**Interfaces:**
- Consumes: 现有 `ApiExceptionHandler`。
- Produces:
  - `@ExceptionHandler(AsyncRequestNotUsableException.class) public void handleClientAbort(AsyncRequestNotUsableException)`（只记 WARN，无堆栈）
  - `handleUnexpected` 在兜底逻辑前检查异常链；命中客户端断开时返回 `null`
  - `static boolean isClientDisconnect(Throwable)`（包级，供测试）

**实现要点：** 沿 `getCause()` 遍历（防止自引用死循环）；`AsyncRequestNotUsableException` 直接判断类型；`ClientAbortException` / `EofException` 按 `getSimpleName()` 判断，不 import Tomcat 类。

- [ ] **Step 1: 写失败测试**

```java
package io.github.cn3shy.text2img.web;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void clientAbortReturnsNoBody() {
        assertNull(handler.handleUnexpected(new AsyncRequestNotUsableException("broken pipe")));
    }

    @Test
    void wrappedClientAbortReturnsNoBody() {
        assertNull(handler.handleUnexpected(new RuntimeException(new AsyncRequestNotUsableException("x"))));
    }

    @Test
    void unexpectedExceptionStillReturns500() {
        ResponseEntity<?> response = handler.handleUnexpected(new IllegalStateException("boom"));

        assertNotNull(response);
        assertEquals(500, response.getStatusCode().value());
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn test -Dtest=ApiExceptionHandlerTest`
Expected: 前两个测试失败（返回了 500 响应而不是 null）。

- [ ] **Step 3: 实现客户端断开识别**

按 Interfaces 修改 `ApiExceptionHandler`；其他分支（`Text2ImgException`、`HttpMessageNotReadableException`、`ErrorResponse`）保持不变。

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2
Expected: 3 个测试通过。

- [ ] **Step 5: 提交（后端仓库）**

```bash
cd D:/Project/Blog/text2img
git add src/main/java/io/github/cn3shy/text2img/web/ApiExceptionHandler.java src/test/java/io/github/cn3shy/text2img/web/ApiExceptionHandlerTest.java
git commit -m "fix: 客户端断开不再按 500 处理"
```

---

### Task 6: 前端 API 模块

**Files:**
- Create: `src/api/text2img.ts`

**Interfaces:**
- Produces:
  - `export type JobStatus = 'running' | 'succeeded' | 'failed'`
  - `export interface Text2ImgJob { jobId: string; status: JobStatus; progress: number | null; stage: string | null; message: string | null }`
  - `export interface CreateJobResponse { jobId: string; status: JobStatus; pollAfterMs: number }`
  - `export class JobExpiredError extends Error`
  - `createJob(input: { prompt: string; width: number; height: number }, signal: AbortSignal): Promise<CreateJobResponse>`
  - `getJob(jobId: string, signal: AbortSignal): Promise<Text2ImgJob>`（404 → 抛 `JobExpiredError`）
  - `getJobImage(jobId: string, signal: AbortSignal): Promise<Blob>`

**实现要点：**
- 请求路径固定 `/api/text2img/jobs`。
- 非 2xx 时解析 `message` / `msg` / `error` / `detail`，失败则回退到 `接口返回 ${status}`。
- `getJobImage` 校验 `content-type` 以 `image/` 开头，否则抛 `接口没有返回图片`。
- 错误详情解析逻辑从 `Text2Img.vue` 迁移到本模块；页面里的 `readErrorDetail` / `stripHtml` 删除。

- [ ] **Step 1: 创建模块**

按 Interfaces 编写，不引入新依赖。

- [ ] **Step 2: 类型检查**

Run: `cd D:/Project/Blog; pnpm exec vue-tsc --noEmit`
Expected: 通过（此时代码尚未被页面引用，允许无使用警告）。

- [ ] **Step 3: 提交（前端仓库）**

```bash
cd D:/Project/Blog
git add src/api/text2img.ts
git commit -m "feat: 增加 text2img 异步任务 API 模块"
```

---

### Task 7: 页面改为轮询 + 平滑进度条

**Files:**
- Modify: `src/pages/app/Text2Img.vue`

**Interfaces:**
- Consumes: Task 6 的 `createJob` / `getJob` / `getJobImage` / `JobExpiredError` / `Text2ImgJob`。

**实现要点：**
- 新增 refs：`progress: number | null`、`stage: string`、`estimate: number`；保留 `generating`、`error`、`history`。
- `displayProgress` computed：`progress !== null` → `Math.min(90, 5 + progress * 0.85)`；否则用 `estimate`。
- 250ms 定时器更新 `estimate = Math.min(70, (Date.now() - pollStartedAt) / 1000)`。
- 轮询循环按设计 6.2：`pollAfterMs` 默认 1000；单次失败重试 3 次（1 秒间隔）；404 → `JobExpiredError` 文案「任务已失效，请重新生成」；10 分钟安全上限「等待超时，请重试」。
- 取消按钮：`AbortController.abort()`，提示「已取消本次生成」。
- 模板：`.t2i-progress span` 改为 `:style="{ width: `${displayProgress}%` }"`；在「正在生成图片…」下显示 `stage || '生成需要一点时间，请稍候。'`。
- CSS：`.t2i-progress span` 去掉 `width: 42%` 和 `animation`，改为 `width: 0; transition: width 0.6s linear;`。
- 保留历史、下载、错误横幅。
- 删除页内不再使用的 `normalizeImage` / `pickImage` / `IMAGE_KEYS` / `stripHtml` / `readErrorDetail`
  （已迁移到 API 模块）。`tsconfig` 开了 `noUnusedLocals`，留着会让类型检查失败。

- [ ] **Step 1: 改写脚本部分**

按实现要点修改 `generate()`、`cancel()`、`onUnmounted` 和状态；删除 `timedOut`、120 秒超时逻辑，以及页面内的 `readErrorDetail` / `stripHtml` / `normalizeImage` / `pickImage` / `IMAGE_KEYS`。

- [ ] **Step 2: 改写模板与样式**

进度条改为确定性宽度；阶段文案上屏；CSS 过渡替换滑动动画。

- [ ] **Step 3: 类型检查**

Run: `cd D:/Project/Blog; pnpm exec vue-tsc --noEmit`
Expected: 通过。

- [ ] **Step 4: 本地手工验证**

Run: 后端已启动（重启后），`pnpm dev` 已在 5173 运行；浏览器打开 `/app/text2img`。
Expected: 提交后进度条从 0 平滑前进；阶段从「排队中」→「采样中 n/8」→「解码图像」；最终出图；取消后提示「已取消本次生成」。

- [ ] **Step 5: 提交（前端仓库）**

```bash
cd D:/Project/Blog
git add src/pages/app/Text2Img.vue
git commit -m "feat: 文生图改为异步轮询并展示采样进度"
```

---

### Task 8: Vercel 统一代理与流式响应

**Files:**
- Create: `api/index.ts`
- Delete: `api/text2img.ts`
- Modify: `vercel.json`

**Interfaces:**
- Produces: 一个处理所有 `/api/*` 的 Vercel Function，把请求原样转发到 `${TEXT2IMG_API_BASE}${path}`，并把响应以流式回传。

**实现要点：**
- 路径：`req.url` 去掉 `/api` 前缀，保留 query；GET/HEAD 无 body。
- 转发 `content-type`、`accept`；`body` 为字符串则原样，为对象则 `JSON.stringify`；有 body 时补 `content-length`。
- 非 insecure：`fetch` + `AbortSignal.timeout(TIMEOUT_MS)`，响应 `Readable.fromWeb(upstream.body).pipe(res)`；
  `upstream.body` 为 null（如 204/HEAD）时直接 `res.end()`。
- insecure（`TEXT2IMG_INSECURE_TLS`）：`https.request` + `rejectUnauthorized: false`，上游响应直接 `pipe(res)`。
- 写头：`res.writeHead(status, { 'content-type': contentType, 'cache-control': 'no-store' })`。
- 上游在写头前失败 → 502 `{"message":"转发到后端失败：..."}`；已开始写流后失败 → `res.destroy()`。
- `vercel.json`：`functions` 改为 `api/index.ts`；`/api/(.*)` 的 destination 改为 `/api/index`，保留其在 SPA 回退 rewrite 之前。
- 删除 `api/text2img.ts`。

- [ ] **Step 1: 新建 `api/index.ts`**

按实现要点编写，`req`/`res` 保持 `any`，不引入 `@vercel/node` 类型依赖。

- [ ] **Step 2: 修改 `vercel.json` 并删除旧函数**

```json
{
  "functions": {
    "api/index.ts": { "maxDuration": 60 }
  },
  "redirects": [ ...保持原样... ],
  "rewrites": [
    { "source": "/api/(.*)", "destination": "/api/index" },
    { "source": "/(.*)", "destination": "/index.html" }
  ]
}
```

- [ ] **Step 3: 构建检查**

Run: `cd D:/Project/Blog; pnpm build`
Expected: `vue-tsc` 与 `vite build` 通过（`api/` 不参与前端构建，但不得破坏构建）。

- [ ] **Step 4: 提交（前端仓库）**

```bash
cd D:/Project/Blog
git add api/index.ts api/text2img.ts vercel.json
git commit -m "feat: 统一 /api 代理并流式转发响应"
```

---

### Task 9: 文档与端到端验收

**Files:**
- Modify: `D:/Project/Blog/docs/api/text2img.md`

**内容要求：**
- 新增「异步任务接口」章节：`POST /text2img/jobs`、`GET /text2img/jobs/{id}`、`GET /text2img/jobs/{id}/image` 的请求/响应示例，`progress` / `stage` 字段说明。
- 改写「超时与取消」：删除 55s / 120s 描述，改为 5 分钟排队 + 100 秒执行 + 前端 10 分钟上限；说明取消只停止前端等待。
- 改写「线上部署」：Vercel Function 改为 `api/index.ts` 统一代理 `/api/*`；说明旧同步接口为 legacy。
- 保留本地联调命令，补充任务接口的 curl 示例。

- [ ] **Step 1: 更新文档**

按内容要求修改，保持现有章节结构。

- [ ] **Step 2: 后端全量测试**

Run: `$env:JAVA_HOME="C:\DEV\jdk\jdk25"; & "C:\DEV\Soft\IDEA\plugins\maven\lib\maven3\bin\mvn.cmd" -f D:\Project\Blog\text2img\pom.xml test`
Expected: 全部通过。

- [ ] **Step 3: 前端类型检查与构建**

Run: `cd D:/Project/Blog; pnpm exec vue-tsc --noEmit; pnpm build`
Expected: 全部通过。

- [ ] **Step 4: 本地端到端验收**

1. 重启后端（IDEA 运行配置不变）。
2. 通过 Vite 代理提交任务并轮询（PowerShell）：

```powershell
$body = '{"prompt":"a cat with an astronaut helmet","width":1024,"height":1024}'
$created = Invoke-RestMethod -Uri "http://localhost:5173/api/text2img/jobs" -Method Post -ContentType application/json -Body $body
$created
do { Start-Sleep -Seconds 1; $job = Invoke-RestMethod "http://localhost:5173/api/text2img/jobs/$($created.jobId)"; $job } while ($job.status -eq 'running')
Invoke-WebRequest "http://localhost:5173/api/text2img/jobs/$($created.jobId)/image" -OutFile "$env:TEMP\t2i-job.png"
```

Expected: 状态依次出现 `排队中` / `采样中 n/8` / `解码图像`；最终 `status=succeeded`、`progress=100`；PNG 有效（约 1MB 以上）。
3. 取消一次生成：后端日志只出现一条客户端断开的 WARN，没有 `HttpMessageNotWritableException`、没有整段堆栈。
4. 轮询期间重启后端：页面提示「任务已失效，请重新生成」。
5. curl 同时提交 5 个任务：第 5 个返回 503。

- [ ] **Step 5: 分别提交两个仓库**

```bash
cd D:/Project/Blog
git add docs/api/text2img.md
git commit -m "docs: 更新异步生图接口文档"
```

后端仓库若本次无改动则无需提交。

---

## 部署（实现完成后由用户执行）

1. 后端：重启 Spring Boot。
2. 前端：Vercel 完整 Redeploy（函数改名必须重新部署）；`TEXT2IMG_API_BASE` 不变。
3. 线上验收：`https://www.3shy.cn/app/text2img` 生成一张超过 60 秒的图，确认成功返回并显示进度。
4. 用 2048×2048 验证流式代理没有被 4.5MB 上限打断。
