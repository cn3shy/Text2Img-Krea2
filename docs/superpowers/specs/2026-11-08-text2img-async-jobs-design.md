# text2img 异步任务 + 进度条设计

日期：2026-11-08
涉及仓库：`D:\Project\Blog\text2img`（后端，独立 git 仓库）、`D:\Project\Blog`（前端 + Vercel）。
前端仓库已用 `.gitignore` 排除 `text2img/`，两个仓库分别提交。

## 1. 背景与目标

线上链路是：浏览器 → Vercel Function `api/text2img.ts` → 隧道 → Spring Boot → ComfyUI。

Vercel Function 内部超时 55 秒、`maxDuration` 60 秒（Hobby 未启用 Fluid Compute），
而 ComfyUI 一次出图实测 65 到 67 秒。函数在 55 秒时主动断开上游连接并返回 502，
后端继续生成到结束，回写时抛出 `ClientAbortException`，图片被丢弃。实测：

- 本地 Vite 代理直连后端：200，64.8 秒，1.26 MB PNG。
- 线上 `https://www.3shy.cn/api/text2img`：502，57.7 秒，`转发到后端失败：上游请求超时`。

本次改造的目标：

1. 出图耗时不再受任何代理/Serverless 超时限制。
2. 前端能看到真实出图进度（采样步数）并平滑展示。
3. 客户端断开不再被当成 500 记录整段堆栈。
4. 保持现有页面交互与历史/下载能力不变。

## 2. 非目标

- 不做多实例部署下的任务共享（任务表是单实例内存）。
- 不做 ComfyUI 任务取消（取消只停止前端等待，ComfyUI 仍会跑完当前任务）。
- 不做账号、鉴权、限流。
- 不支持 EdgeOne 侧的 API 代理（EdgeOne 不读 `vercel.json`，本次不扩展）。
- 不改变 ComfyUI 只监听本机、不外网暴露的现状。

## 3. 约束

- 后端进程与 ComfyUI 在同一台机器，ComfyUI 地址仍是 `http://127.0.0.1:8188`。
- 后端为单实例，进程重启会丢失进行中的任务。
- 不新增第三方依赖；WebSocket 客户端用 JDK 自带 `java.net.http.WebSocket`。
- 后端生成超时沿用 `comfyui.generation-timeout`（默认 100 秒），从提交到 ComfyUI 的时刻开始计时。
- 前端线上部署在 Vercel（Hobby），单个请求必须远小于 60 秒。
- 浏览器与后端之间仍是同源 `/api/*`，由 Vite dev proxy / Vercel 转发。

## 4. 总体链路

```text
浏览器
  → POST /api/text2img/jobs        （立即返回 jobId）
  → GET  /api/text2img/jobs/{id}   （每秒轮询，带 progress/stage）
  → GET  /api/text2img/jobs/{id}/image（成功后取 PNG）

后端
  → POST /prompt 提交 ComfyUI（请求线程内完成，失败立即返回）
  → 后台线程轮询 /history/{promptId} → /view 下载图片 → 写入任务
  → 同时连接 ws://127.0.0.1:8188/ws?clientId={jobId} 收集进度
```

每个 HTTP 请求都是短请求，Vercel Function 的 60 秒上限不再参与出图耗时。

## 5. 后端设计

### 5.1 接口契约

所有路径都挂在 `/text2img` 下（Vite/Vercel 会剥掉 `/api` 前缀）。

#### POST `/text2img/jobs`

请求体沿用 `Text2ImgRequest`：

```json
{ "prompt": "一只橘猫", "width": 1024, "height": 1024 }
```

- 参数校验规则不变（`RequestValidator`）：prompt 必填、≤1200 码点；尺寸缺省 1024，
  范围 256–2048 且为 8 的倍数。
- 请求线程内完成 ComfyUI `POST /prompt`。失败时按原错误返回：参数错误 400、
  ComfyUI 不可用/拒绝工作流 503。
- 成功返回 `202 Accepted`：

```json
{ "jobId": "5f0c9d1e-...", "status": "running", "pollAfterMs": 1000 }
```

同时返回 `Location: /text2img/jobs/{jobId}`。

- 任务容量不足时返回 503，`{"message": "当前生成任务较多，请稍后再试"}`。

#### GET `/text2img/jobs/{jobId}`

- 200：

```json
{
  "jobId": "5f0c9d1e-...",
  "status": "running",
  "progress": 37,
  "stage": "采样中 3/8",
  "message": null
}
```

字段约定：

- `status`：`running` | `succeeded` | `failed`。
- `progress`：0–100 的整数；尚未收到任何采样进度时为 `null`。
- `stage`：阶段文案，可能为 `null`。
- `message`：失败原因；成功或进行中为 `null`。

- 状态响应带 `Cache-Control: no-store`，避免代理或浏览器缓存轮询结果。
- 任务不存在或已过期：404，`{"message": "任务不存在或已过期"}`。

#### GET `/text2img/jobs/{jobId}/image`

- 成功且图片已就绪：200，`Content-Type: image/png`，`Cache-Control: no-store`。
- 任务不存在/过期：404。
- 任务仍在进行或已失败：409，`{"message": "图片还没准备好"}`。
  前端先轮询状态接口，失败时不会来取图。
- 该映射**不要写 `produces = image/png`**，由 `ResponseEntity` 显式设置 Content-Type，
  避免异常分支出现内容协商问题。

#### 兼容入口

- 保留同步 `POST /text2img`，行为不变，文档标注为 legacy。
- 新前端不再调用它；保留是为了 curl、旧文档和已有脚本。

### 5.2 任务模型与存储

新增 `service/GenerationJob.java`：

- 字段：`id`、`promptId`、`width`、`height`、`status`、`progress`、`stage`、`image`（`byte[]`）、
  `message`、`createdAt`、`executedAt`、`finishedAt`。
- **所有可变字段都用 `volatile` 发布**（`status`、`progress`、`stage`、`image`、`message`、
  `executedAt`、`finishedAt`），执行线程写、HTTP 线程读；写入顺序为「先写内容字段，最后写
  `status`」，读取时先读 `status` 再读其余字段。不依赖 `ConcurrentHashMap` 提供可见性保证。
- 状态只由执行线程推进：创建即 `running`，成功改 `succeeded`，异常改 `failed`。

新增 `service/GenerationJobStore.java`（`@Component`）：

- `ConcurrentHashMap<String, GenerationJob>`。
- 清理策略：每次提交/查询时顺带清理「已结束且 `finishedAt` 超过 5 分钟」的任务。
- 保留中的任务总数最多 16 个（含已结束）；总数超过上限且有已结束任务 → 淘汰最老的已结束任务。
- **准入限制由 `GenerationJobService` 里的共享 `Semaphore(4)` 负责**：1 个执行 + 最多 3 个
  在 ComfyUI 队列里等待；第 5 个提交返回 503。
- 旧同步接口不建任务，但也必须共享同一个信号量（见 5.3），否则可以绕过上述限制。
- 图片字节随任务保留到过期，单张约 1.2 MB，最坏约 16 × 1.2 MB。
- 进程重启后任务全部丢失（文档写明）。

新增 `service/GenerationJobService.java`（`@Service`）：

- 持有 `ExecutorService`，使用 `Executors.newVirtualThreadPerTaskExecutor()`，
  `@PreDestroy` 关闭。
- `submit(Text2ImgRequest)`：校验 → 占位建任务 → 建 workflow → 连接进度 WS →
  提交 ComfyUI → 启动后台线程 → 返回任务。
- `get(jobId)` / `getImage(jobId)`。

### 5.3 提交与执行流程

请求线程（`submit`）：

1. `RequestValidator.validate` → `GenerationRequest`。
2. 从共享 `Semaphore(4)` `tryAcquire()`；失败 → 503。随后创建任务，
   `clientId = jobId`（唯一，避免多个任务进度串台）。
3. `WorkflowBuilder.build(prompt, width, height)`（逻辑不变）。
4. 异步发起 WebSocket 连接（不阻塞提交；连不上只影响进度，不影响出图）。
5. `POST /prompt` 提交（沿用现有 `submit` 逻辑，抽出可复用方法），把 `prompt_id`
   写回任务，`stage = "排队中"`。
6. 提交失败：从任务表移除该任务、释放信号量许可，按原语义抛 `UpstreamException`。
7. 启动后台线程执行 `awaitImage`，返回 202。

后台线程：

1. 阶段判定与计时（必须分成两段，排队时间不能算作生成超时）：
   - 排队阶段：从提交起最多 `comfyui.queue-timeout`（新增配置，默认 5 分钟）。
   - 执行阶段：从任务真正开始执行起最多 `comfyui.generation-timeout`（默认 100 秒）。
   - `executedAt` 的判定：WS 收到 `execution_start` 时写入；WS 不可用时，worker 在任务尚未
     出现在 history 时顺带查一次 ComfyUI `/queue`，`prompt_id` 出现在 `queue_running` 里
     即写入。两个来源取先到的。
   - 排队超时 → `message = "排队超时，请稍后重试"`；执行超时 → `"生成超时，请稍后重试"`。
   - 若 WS 和 `/queue` 都拿不到状态，以排队超时兜底，避免任务永久占用名额。
2. 轮询 `/history/{promptId}`，直到任务出现在 history 或触发上述超时。
3. 从 outputs 找图（优先 `type=output`），`GET /view` 下载 PNG。
4. 成功：写入 `image`，`progress=100`，`stage="完成"`，记录 `finishedAt`，最后写
   `status=succeeded`。
5. 失败：写入 `message`（`Text2ImgException` 的消息可以直接展示；其他异常统一为
   「服务器内部错误，请稍后重试」），记录 `finishedAt` 并记一条日志，最后写 `status=failed`。
6. `finally`：关闭 WebSocket 连接，释放并发额度。

超时判定用提交时间和 `executedAt`，不要再用 `startedAt + generationTimeout` 的单段写法。

**并发额度**：`GenerationJobService` 持有一个 `Semaphore(4)`。异步提交先在信号量上调
`tryAcquire()` 占位，任务结束后释放；旧同步 `generate` 也必须走同一个信号量（拿不到立即
503），否则旧接口可以绕过容量限制。信号量是唯一权威计数，任务表的运行中数量只做展示。

`ComfyUiProperties` 新增 `queueTimeout`（`comfyui.queue-timeout`，默认 `5m`），
`application.yaml` 同步补一行。同步接口 `generate` 复用同一套「提交 + 等待下载」私有方法，
但不建任务、不连 WS，超时仍按单段 `generation-timeout` 处理（本地/legacy 用法）。

### 5.4 进度采集（ComfyUI WebSocket）

新增 `service/ComfyUiProgressListener.java`：

- 连接地址由 `comfyui.base-url` 推导：`http→ws`、`https→wss`，追加
  `/ws?clientId={jobId}`。
- 使用 `HttpClient.newWebSocketBuilder().connectTimeout(2s)`，连接失败只记 debug 日志。
- 只处理文本消息；二进制（预览图）忽略。每条消息处理后 `request(1)` 继续接收。
- 消息为 JSON，按 `type` 处理：
  - `execution_start` → `stage="开始生成"`，并写入任务的 `executedAt`（执行阶段计时起点）。
  - `executing`：`data.node` 为节点 id 时，根据提交的 workflow 查 `class_type` 映射阶段；
    `data.node` 为 null 时 `stage="收尾中"`。
  - `progress`：`data.value` / `data.max` → `progress = round(value*100/max)`，
    `stage = 阶段名 + " " + value + "/" + max`（如「采样中 3/8」）。
  - 其他类型忽略。
- 带 `prompt_id` 的事件必须与任务的 `promptId` 一致，否则忽略（ComfyUI 会广播所有客户端的
  执行事件）。
- 节点阶段映射（由 `class_type` 决定，不硬编码节点 id）：
  - `KSampler` / `KSamplerAdvanced` → `采样中`
  - `VAEDecode` / `VAEDecodeTiled` → `解码图像`
  - `SaveImage` → `保存图像`
  - 名称以 `Loader` 结尾（`UNETLoader` / `CLIPLoader` / `VAELoader` / `LoraLoader`）→ `加载模型`
  - `CLIPTextEncode` → `编码提示词`
  - 其他 → 不改变阶段
- 进度只在采样阶段有值；加载/解码阶段保留最后一次采样百分比（前端负责把最后一段留白，
  见 6.2）。

注意：当前模板只有一个 `KSampler`（8 步），所以采样进度是 8 段。将来模板加入第二个采样器时，
进度会按节点重新从 0 开始，属于可接受行为。

### 5.5 错误与状态码

| 场景 | HTTP | 响应体 |
| --- | --- | --- |
| 参数非法 | 400 | `{"message": "..."}` |
| ComfyUI 不可用/拒绝工作流 | 503 | `{"message": "..."}` |
| 任务容量已满 | 503 | `{"message": "当前生成任务较多，请稍后再试"}` |
| 任务不存在/过期 | 404 | `{"message": "任务不存在或已过期"}` |
| 图片未就绪/任务失败 | 409 | `{"message": "图片还没准备好"}` |
| 排队超时 | 状态接口 `failed` | `message = "排队超时，请稍后重试"` |
| 执行超时 | 状态接口 `failed` | `message = "生成超时，请稍后重试"` |

新增异常：`JobNotFoundException`（404）、`JobNotReadyException`（409），继承
`Text2ImgException`，由现有 `ApiExceptionHandler` 统一处理。

### 5.6 代码结构

后端改动文件：

- 新增 `web/Text2ImgJobController.java`
- 新增 `service/GenerationJob.java`
- 新增 `service/GenerationJobStore.java`
- 新增 `service/GenerationJobService.java`
- 新增 `service/ComfyUiProgressListener.java`
- 新增 `error/JobNotFoundException.java`
- 新增 `error/JobNotReadyException.java`
- 修改 `service/ComfyUiText2ImgService.java`：抽出可复用的提交/等待逻辑，增加按 `clientId`
  提交、返回 `promptId` 和 `ImageReference` 的入口；`generate` 保持行为不变。
- 修改 `web/ApiExceptionHandler.java`：客户端断开处理（见第 8 节）。

## 6. 前端设计（`D:\Project\Blog`）

### 6.1 API 模块

新增 `src/api/text2img.ts`：

```ts
export type JobStatus = 'running' | 'succeeded' | 'failed'

export interface Text2ImgJob {
  jobId: string
  status: JobStatus
  progress: number | null
  stage: string | null
  message: string | null
}

export async function createJob(
  input: { prompt: string; width: number; height: number },
  signal: AbortSignal,
): Promise<{ jobId: string; pollAfterMs: number }>

export async function getJob(jobId: string, signal: AbortSignal): Promise<Text2ImgJob>

export async function getJobImage(jobId: string, signal: AbortSignal): Promise<Blob>
```

- 非 2xx 时复用 `readErrorDetail` 的解析逻辑（从 `Text2Img.vue` 移到该模块），
  抛出带服务端 `message` 的 `Error`。
- `getJobImage` 校验 `Content-Type` 以 `image/` 开头。

### 6.2 页面状态机与进度平滑

`src/pages/app/Text2Img.vue` 的 `generate()`：

1. `createJob` → 进入 `generating`，启动进度估算定时器（250ms 一次）。
2. 循环：等待 `pollAfterMs`（可被 `AbortSignal` 中断）→ `getJob`，更新 `progress` / `stage`。
3. `succeeded` → `getJobImage` → `URL.createObjectURL` → 写入历史；进度置 100 后展示。
4. `failed` → 抛错，展示 `job.message`。
5. 单次轮询失败（网络错误或 5xx）：最多连续重试 3 次、每次间隔 1 秒；仍失败则提示
   「暂时无法查询生成状态，任务可能仍在生成」并结束等待。**404 视为任务已失效**，
   直接提示「任务已失效，请重新生成」，不重试；不能把「查不到任务」和「任务失败」混为一谈。
6. 循环整体受 10 分钟安全上限约束（覆盖 5 分钟排队 + 100 秒生成 + 网络余量），
   超时提示「等待超时，请重试」。
7. `finally` 清理定时器与 controller。

进度显示规则（保留最后 10% 给解码/保存阶段，避免采样一结束就卡在 100%）：

- 任务成功：显示 100%。
- 有真实进度：`display = min(90, 5 + progress * 0.85)`。
- 还没有真实进度：`display = min(70, 已等待秒数 × 1)`，每 250ms 更新，让条自己往前走。
- 进度条宽度使用 CSS `transition: width 0.6s linear`，实现平滑；真实进度到达后可能出现
  一次小幅回落，属于预期。
- 第一帧到第一次状态返回之间显示现有的不确定动画（`.t2i-progress` 的滑动动画）。

阶段文案显示在「正在生成图片…」下方，如：「采样中 3/8」；`stage` 为 null 时显示
「生成需要一点时间，请稍候。」。

取消按钮：`AbortController.abort()`，停止轮询并提示「已取消本次生成」；不通知后端。

### 6.3 保持不变

- 页面布局、状态徽标、历史缩略图、下载链接、错误横幅样式都不变。
- `Accept: application/json, image/*` 不变。
- 请求仍走同源 `/api/text2img/...`，Vite dev proxy 不需要改。

## 7. Vercel 改造（`D:\Project\Blog`）

普通 Vercel Function 不支持 `[...path]` catch-all（已查证：非 Next.js 项目里
`api/[...slug].ts` 不会匹配多级路径）。采用「单文件函数 + rewrite」方案：

1. 删除 `api/text2img.ts`，新增 `api/index.ts`，作为 `/api/*` 的统一代理：
   - 上游路径 = 请求路径去掉 `/api`，拼到 `TEXT2IMG_API_BASE` 后；保留 query string。
   - 转发 method、`content-type`、`accept`、body（GET 无 body）。
   - 回包转发 status、`content-type`、`cache-control: no-store`。
   - **响应体必须流式转发**：`res.writeHead(status, headers)` 后把上游 body pipe 到 `res`，
     不要整体缓冲后 `res.send`。Vercel Function 对缓冲响应有 4.5MB 上限
     （`FUNCTION_RESPONSE_PAYLOAD_TOO_LARGE`），而 2048×2048 的 PNG 很可能超过
     （1024×1024 实测 1.26MB）。流式响应不受该上限约束。上游在写响应头之前失败才返回
     502 JSON；已经开始写流后再失败只能销毁连接。
   - 保留 `TEXT2IMG_INSECURE_TLS` 自签证书开关和 `TEXT2IMG_TIMEOUT_MS`（默认 55s）。
     现在所有请求都是短请求，55s 只是兜底。
2. `vercel.json`：
   - `functions` 改为 `"api/index.ts": { "maxDuration": 60 }`。
   - 把 `{"source": "/api/(.*)", "destination": "http://101.200.74.221:8090/$1"}`
     改为 `{"source": "/api/(.*)", "destination": "/api/index"}`（保持它在 SPA 回退 rewrite
     之前）。原 IP 直连实测不可达，且与 `TEXT2IMG_API_BASE` 不一致。
3. `public/vercel.json` 是静态副本，不参与 Vercel 路由；本次不改，在文档里注明。

部署后 `TEXT2IMG_API_BASE` 环境变量不需要改。

## 8. 后端日志噪音修复

`web/ApiExceptionHandler.java`：

- 新增 `@ExceptionHandler(AsyncRequestNotUsableException.class)`，返回 `void`：
  只记一条 WARN（不带堆栈）：「客户端已断开，未发送响应: ...」。
- 在兜底 `handleUnexpected` 开头检查异常链：如果包含
  `AsyncRequestNotUsableException`、`ClientAbortException`（按类名判断，避免绑定 Tomcat）
  或 `EofException`，记一条 WARN 并返回 `null`，不尝试写响应体。
- 其他异常行为不变（400/404/405/415 映射、5xx 日志）。

## 9. 兼容性

- 同步 `POST /text2img` 保留，行为不变。
- 前端旧的 `pickImage` / JSON 兜底解析可以保留，但新流程固定返回 PNG；
  如果实现时发现它们已无用，可在同一 PR 内删除（不影响外部契约）。
- 文档 `docs/api/text2img.md` 补充异步协议、进度字段、Vercel 路由变化和部署验证步骤。

## 10. 测试与验收

后端自动化测试（新增 `src/test/java/...`，使用 `spring-boot-starter-test`）：

1. `Text2ImgJobControllerTest`（`@WebMvcTest` + mock `GenerationJobService`）：
   - POST 返回 202、`Location` 和 `jobId`。
   - 未知任务 GET 返回 404。
   - 图片未就绪返回 409；就绪返回 `image/png` 和字节内容。
   - 容量已满时 POST 返回 503。
2. `GenerationJobStoreTest`：容量上限、淘汰、5 分钟过期清理。
3. `GenerationJobServiceTest`：并发准入（第 5 次 `tryAcquire` 失败 → 503 语义）、
   两段超时的边界（排队超时与执行超时分别触发正确的 `message`）。
4. `ApiExceptionHandlerTest`：`AsyncRequestNotUsableException` 不产生 5xx JSON；
   普通异常仍然产生 500 JSON。
5. `RequestValidator` / `WorkflowBuilder` 既有逻辑不变，不强制补测。

端到端手工验收（本地）：

1. 启动后端与 ComfyUI；通过 `http://[::1]:5173/api/text2img/jobs` 提交一个 1024×1024 任务。
2. 每秒查询状态，确认能看到 `progress` 递增、`stage` 从「排队中」→「采样中 n/8」→「解码图像」。
3. 任务成功后下载 `/image`，确认是有效 PNG（约 1 MB 以上）。
4. 在浏览器里完整走一遍页面：进度条平滑前进、出图、历史、下载。
5. 等待中途点「取消」：页面提示已取消；后端日志只出现一条客户端断开的 WARN，
   不再有 `ClientAbortException` 整段堆栈和 `HttpMessageNotWritableException`。
6. 构造一次超过 60 秒的出图，确认整个链路不受 60 秒限制。
7. 轮询期间重启后端：前端应提示「任务已失效，请重新生成」，不白屏、不卡死。
8. 用 curl 同时提交 5 个任务：前 4 个进入排队，第 5 个返回 503 和提示文案。

线上验收（部署后）：

1. Vercel 重新部署（函数文件改名必须完整 redeploy）。
2. 打开 `https://www.3shy.cn/app/text2img`，输入 prompt 生成，确认超过 60 秒也能返回图片。
3. Network 里确认 `/api/text2img/jobs` 短请求快速返回（202），轮询请求均为毫秒级。
4. 取消一次生成，确认前端提示正常。
5. 构造一张接近或超过 4.5MB 的图（比如 2048×2048），确认流式代理能完整返回，
   不被 Vercel 的缓冲响应上限打断。

## 11. 部署步骤

1. 后端：重启 Spring Boot（IDEA 运行配置不变）。
2. 前端：提交 `Blog` 仓库改动 → Vercel redeploy；确认环境变量 `TEXT2IMG_API_BASE` 仍指向后端公网地址。
3. 确认 Vercel Functions 里出现 `api/index.ts`、`api/text2img.ts` 已消失。
4. 按第 10 节做线上验收。

## 12. 已知限制与风险

- 任务表在内存里：进程重启丢失；多实例部署会查不到任务。
- 取消失败于「停止等待」语义：ComfyUI 仍会跑完当前任务，GPU 占用不会立即释放。
- 进度依赖 ComfyUI WebSocket：连不上时只有时间估算，出图不受影响。
- 前端轮询约 1 秒一次，单个任务约 65 次请求；均为短请求，Vercel 调用次数会上升。
- 单 GPU 容量：同时最多接受 4 个任务（1 执行 + 3 排队），第 5 个返回 503；
  排队最长 5 分钟，超时后任务失败但 ComfyUI 可能仍在执行。
- 后端保留的图片字节会占用内存，靠容量上限和过期清理兜底。
- 如果 ComfyUI 模板改成多采样器工作流，进度会按节点重置（可接受）。
- 代理改为流式转发后，图片大小不再受 Vercel 4.5MB 缓冲上限约束；但 2048×2048
  在本机 `--lowvram` 配置下本身可能很慢甚至 OOM，这不是本次改造引入的问题。
- `api/index.ts` 会代理整个 `/api/*` 命名空间；EdgeOne 侧仍然没有对应的函数或反代配置。

## 13. 实现时由构建者决定的事项（已定）

以下为实现细节，写在这里避免实现时再讨论：

- 任务 id 使用 `UUID.randomUUID().toString()`。
- 后台线程使用虚拟线程执行器；并发额度由共享 `Semaphore(4)` 控制，任务表计数不参与准入。
- 同时接受任务上限 4（1 执行 + 3 排队），保留任务总数上限 16，结束任务保留 5 分钟。
- 超时两段计时：排队 `queue-timeout`（默认 5 分钟，从提交算），执行 `generation-timeout`
  （默认 100 秒，从 `executedAt` 算）。
- 前端轮询间隔使用服务端返回的 `pollAfterMs`（默认 1000ms）。
- 前端安全上限 10 分钟；轮询失败重试 3 次 × 1 秒，404 终止。
- Vercel 代理流式转发响应体，不整体缓冲。
- 旧同步接口共享同一并发额度，文档标注为 legacy、不建议线上使用。
- 状态接口与图片接口都返回 `Cache-Control: no-store`。
- 阶段文案使用中文，与现有界面一致。

## 14. 备选方案（已否决）

**前端直连 ComfyUI（经樱花 frp）**：技术上可行，进度可直接用 ComfyUI WS，且少一跳。
否决原因：

- 本机 ComfyUI API 完全无鉴权（实测 `/prompt`、`/history`、`/queue` 均开放），
  且当前未启用 CORS（预检 403）。
- 直接挂公网等于把「可提交任意 workflow 的节点执行环境」暴露出去，自定义节点可读写本地文件。
- 浏览器要求隧道必须是有效 HTTPS 证书，自签证书无法像 Vercel 函数那样跳过校验。
