# resilient-downstream-guard-java

面向**本地**下游依赖的调用保护组件（Spring Boot 3 / Java 21）。下游以可注入行为的本地模拟实现配合验证，
在**超时、持续故障、并发压力与恢复**过程中给出一致且可解释的结果。

能力总览：

- 按下游维度独立的**并发隔离（Bulkhead）**与**熔断器（Circuit Breaker）**；
- **降级结果可区分原因**：熔断拒绝 / 半开探测拒绝 / 隔离舱拒绝 / 调用超时 / 等待在途结果超时 / 真实下游失败；
- **重复请求保护**：同一业务键并发提交或超时重试时，下游副作用最多执行一次，成功结果可重放；
- **跨线程上下文**：超时后下游在后台线程继续执行时仍能关联同一次请求（requestId / businessKey）；
- **可观测指标**：成功、失败、超时、降级、拒绝、复用计数与熔断状态按下游可查询，并发安全、内存有界。

---

## 1. 总体执行流程

`DownstreamGuard.execute(downstream, businessKey, timeoutMillis, supplier)` 的处理顺序：

1. **幂等准入**（有 businessKey 时）：按业务键获取
   `LEADER`（真正执行）/ `FOLLOWER`（等待并复用在途结果）/ `REPLAY`（TTL 内成功结果直接重放）。
2. **熔断许可**：`CircuitBreaker` 基于状态与统计决定放行或拒绝（非阻塞）。
3. **隔离舱占位**：`Bulkhead` 信号量 `tryAcquire`，名额耗尽立即拒绝，不排队等待。
4. **后台执行 + 限时等待**：真实调用提交到带上下文传播的调度线程池，调用方线程最多等待 `timeoutMillis`。
5. **结果归类与统计回写**：成功 / 真实失败 / 各类拒绝分别映射为不同结局，并更新指标与熔断窗口。

无 businessKey 的调用跳过第 1 步，每次都真实执行（仍受熔断、隔离、超时保护）。

---

## 2. 熔断规则

每个下游独立持有一个熔断器，状态为 `CLOSED → OPEN → HALF_OPEN → CLOSED / OPEN`。
判定基于**定长滑动窗口**（只保留最近 N 次，窗口满后最老记录被覆盖，长期运行不累积对象）与可注入时钟。

| 规则 | 配置项 | 默认值 | 含义 |
|---|---|---|---|
| 窗口大小 | `guard.circuit.window-size` | 20 | 最近 N 次调用参与统计 |
| 最小样本数 | `guard.circuit.minimum-number-of-calls` | 5 | 样本不足不熔断，避免冷启动误判 |
| 失败率阈值 | `guard.circuit.failure-rate-threshold` | 0.5 | 窗口内失败率 ≥ 阈值时 CLOSED→OPEN |
| 打开冷却 | `guard.circuit.wait-in-open` | 5s | OPEN 后该时间内一律快速失败 |
| 半开探测数 | `guard.circuit.half-open-permitted-calls` | 3 | HALF_OPEN 允许同时在途的探测数 |

状态流转（所有检查与迁移在同一把锁内完成，不抖动、不重复计时）：

- **CLOSED**：调用数达到最小样本且失败率 ≥ 阈值 → 进入 **OPEN**，记录打开时间。
- **OPEN**：冷却未结束 → 全部返回 `CIRCUIT_OPEN`，**不触达下游**；
  冷却到期后的第一个请求把状态切到 **HALF_OPEN** 并占用一个探测名额。
- **HALF_OPEN**：最多允许 N 个在途探测，名额满后的请求返回 `HALF_OPEN_PROBE_REJECTED`；
  - **任一探测失败** → 立即回到 **OPEN** 并重新计时冷却（防止反复探测抖动）；
  - **N 个探测全部成功** → 回到 **CLOSED** 并清空统计窗口；探测未全部成功前保持 HALF_OPEN。

> 时间判定走 `TimeSource` 抽象：生产用系统时钟，测试用 `TestTimeSource` 手动推进，冷却与恢复可复现。

---

## 3. 隔离（Bulkhead）与降级原因

- 每个下游独立信号量，默认最大并发 `guard.bulkhead-max-concurrent=10`，`tryAcquire` 不等待；
  名额耗尽立即返回降级 `BULKHEAD_REJECTED`，因此**慢下游占满自身名额不会拖垮其它下游**。
- 名额在调用结束（成功 / 异常 / 超时后的后台完成回调）时归还；调用方超时**不会取消**后台任务
  （避免中断导致的不确定副作用），而是在后台真正结束时归还名额并回写统计。

调用结局通过 `GuardResult` 表达，字段 `outcome = SUCCESS / FALLBACK / FAILURE`：

| 场景 | outcome | `reason`（FallbackReason） | 是否触达下游 |
|---|---|---|---|
| 成功 | SUCCESS | null | 是（或复用） |
| 熔断打开期请求 | FALLBACK | `CIRCUIT_OPEN` | 否（拒绝） |
| 半开探测名额满 | FALLBACK | `HALF_OPEN_PROBE_REJECTED` | 否（拒绝） |
| 隔离舱名额耗尽 | FALLBACK | `BULKHEAD_REJECTED` | 否（拒绝） |
| 调用超时（leader/无键） | FALLBACK | `TIMEOUT` | 已发起，后台继续 |
| 等待同键在途结果超时 | FALLBACK | `IN_FLIGHT_TIMEOUT` | 否（复用请求） |
| 下游真实抛错 | FAILURE | null，错误在 `errorMessage` | 是（真实失败） |

“被拒绝”（前 3 类）与“真正失败”因此可严格区分；指标中 `rejected` 只累计前 3 类。

---

## 4. 重复请求保护语义（幂等）

按 `businessKey` 去重，注册表 `IdempotentRegistry`：

- **同键并发提交**：只有一个 `LEADER` 真正执行下游，其余 `FOLLOWER` 等待并复用同一个 `CompletableFuture`
  的结果；返回结果 `duplicate=true`。
- **超时重试**：leader 超时仅让**本次调用**返回 `TIMEOUT`，但**不完成、不取消**在途 future；
  下游在后台继续执行。此时重试为 follower：
  - 下游尚未完成 → 等待也可能超时，返回 `IN_FLIGHT_TIMEOUT`（标记 duplicate，不发起新调用）；
  - 下游已完成 → 复用同一成功结果。
  因此“**下游副作用最多执行一次，也不会漏处理**”。
- **成功结果**：在 `guard.idempotent-ttl`（默认 30s）内对后续请求直接重放（`REPLAY`，duplicate=true）。
- **失败结果不缓存**：异常完成后条目立即移除，下游恢复时同键可**真正再执行一次**并成功。
- **内存有界**：容量上限 `guard.idempotent-max-entries`（默认 10000）；惰性淘汰已完成且过期的条目，
  超容量时淘汰最早完成的条目，**在途条目不淘汰**。

---

## 5. 跨线程上下文

- `RequestContextHolder` 基于 `InheritableThreadLocal` 保存 `requestId / businessKey`。
- `TracingExecutor` 包装调度线程池：提交任务时**快照**当前上下文，任务在后台线程执行前**恢复**、
  执行后清理。超时后下游在后台线程继续跑时，日志与结果仍能关联同一请求。
- `GuardResult.context` 回传发起请求的 `requestId / businessKey`。

---

## 6. 可观测指标

`GET /demo/metrics` 或 `DownstreamGuard.metricsAll()`，按下游输出：

| 指标 | 含义 |
|---|---|
| `total` | 真实执行结局计数 = success + failure + timeout（不含复用/拒绝） |
| `success` | 真实下游调用成功并完成的次数 |
| `failure` | 真实触达下游但失败的次数（**不含**拒绝） |
| `timeout` | 调用方等待超时的次数 |
| `fallback` | 走降级分支的次数（超时 + 各类拒绝，含 IN_FLIGHT_TIMEOUT） |
| `rejected` | 在触达下游前被守卫拒绝的次数（熔断 / 半开名额 / 隔离舱） |
| `duplicate` | 复用同键在途结果或 TTL 重放的次数（未产生新的下游副作用） |
| `circuit.state` | CLOSED / OPEN / HALF_OPEN |
| `circuit.openedAt` / `nextProbeAt` | 打开时间 / 下一个可探测时间（毫秒，TimeSource） |
| `circuit.activeProbes` | 半开期在途探测数 |
| `circuit.windowFailures` / `windowTotal` / `failureRatio` | 当前滑动窗口失败数 / 总数 / 失败率 |

计数使用同步累加（`DownstreamMetrics`），并发下不丢失；每下游固定一份指标对象，结构有界。

---

## 7. 本地验证方法

### 7.1 自动化测试

```bash
mvn test
```

覆盖场景（共 23 个用例，时间判定由 `TestTimeSource` 控制，可复现）：

- `CircuitBreakerTest`：阈值判定、冷启动不误判、冷却期快速失败、半开探测受控、探测失败重开、恢复关闭；
- `CircuitRecoveryIntegrationTest`：经守卫门面的 OPEN→HALF_OPEN→CLOSED 恢复、探测失败重开、下游互不影响；
- `BulkheadTest`：名额耗尽返回 `BULKHEAD_REJECTED`、超额不触达下游、名额释放恢复、下游间隔离；
- `ConcurrentTimeoutTest`：慢调用超时、超时重试复用在途结果、同键并发副作用仅一次；
- `ContextPropagationTest`：超时后后台线程仍携带同一 requestId、失败不缓存可真正重试；
- `MetricsConsistencyTest`：高并发计数准确、拒绝与失败区分、TTL 清理、缓存容量有界；
- `SpringEndToEndTest`：Spring 装配后的健康/故障/超时/恢复与指标端到端验证。

测试日志会打印模拟下游的状态流转与关键输入，例如：

```
[mock:INVENTORY] behavior switched -> latency=0ms error=true
[mock:INVENTORY] invoke seq=1 key=err-1 requestId=... latency=0ms error=true
```

### 7.2 手工 HTTP 验证

启动（端口可改，避免与本机冲突）：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=18765"
```

```bash
B=http://localhost:18765/demo

# 查看下游当前行为与真实调用次数
curl -s $B/downstreams

# 健康调用
curl -s -X POST "$B/call/PROFILE?key=u1&timeout=1000"

# 让 POINTS 变慢(300ms)，用 80ms 超时触发 TIMEOUT 降级
curl -s -X POST "$B/mock/POINTS?latency=300"
curl -s -X POST "$B/call/POINTS?key=dedup-1&timeout=80"

# 等后台跑完后用同一 key 重试：duplicate=true，POINTS invocationCount 仍为 1
sleep 1
curl -s -X POST "$B/call/POINTS?key=dedup-1&timeout=1000"

# 让 INVENTORY 持续故障：前几次 outcome=FAILURE，达到阈值后 reason=CIRCUIT_OPEN
curl -s -X POST "$B/mock/INVENTORY?error=true&message=inventory-down"
for i in 1 2 3 4 5 6; do curl -s -X POST "$B/call/INVENTORY?key=e$i&timeout=500"; echo; done

# 查看指标（含熔断状态）与真实副作用次数
curl -s $B/metrics
curl -s $B/downstreams
```

### 7.3 主要配置（application.properties 可用 `-D` 或属性覆盖）

| 属性 | 默认 |
|---|---|
| `guard.default-timeout` | 2s |
| `guard.bulkhead-max-concurrent` | 10 |
| `guard.idempotent-ttl` | 30s |
| `guard.idempotent-max-entries` | 10000 |
| `guard.circuit.window-size` | 20 |
| `guard.circuit.minimum-number-of-calls` | 5 |
| `guard.circuit.failure-rate-threshold` | 0.5 |
| `guard.circuit.wait-in-open` | 5s |
| `guard.circuit.half-open-permitted-calls` | 3 |

---

## 8. 代码结构

```
guard/
  core/         时钟抽象、下游标识、熔断器状态、降级原因、拒绝信号异常
  model/        GuardResult / CircuitStatus / DownstreamMetrics
  config/       熔断规则、GuardProperties、自动装配
  circuit/      SlidingWindow、CircuitBreaker
  bulkhead/     Bulkhead
  idempotent/   InFlight、IdempotentRegistry
  trace/        RequestContext、Holder、TracingExecutor
  metrics/      GuardMetricsRegistry
  DownstreamGuard.java   门面（编排上述能力）
downstream/     DownstreamService、可注入 LocalMockDownstreamService、BehaviorSpec、BusinessService
web/            GuardController（本地手工验证入口）
tests/support/  TestTimeSource、GuardTestFactory
```
