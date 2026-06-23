# BV2 OpenCL 性能剖析与加速计划 (Snapdragon 8 Gen 3)

## 目标

基于流量剖析（非遮罩），验证 Snapdragon 8 Gen 3 上 BV2-22k 的 MNN OpenCL 推理加速
是否可达到稳态 RTF ≤ 0.8× CPU 基线（RTF ~0.22）；若不可行，则生成可复现的
否定证据 ADR 并还原至 `AUTO` 安全默认值。

## 无可争议的事实（Phase 1 前已验证）

- MNN v3.5.0，`MNN_GPU_TIME_PROFILE=ON`，已在设备上产生每个事件的原生
  `kernel time = %d us %s` logcat 输出 — 但未聚合，无 GWS/LWS。
- 所有先前测试的 OpenCL 模式的设备端 wall‑clock RTF 均 ≥ 0.53；
  均未达到 CPU 基线 RTF ~0.28。
- `materialize_to_target` 执行 `readMap<float>` 主机往返，违反了 "禁止循环内有 map" 规则。
- 分析克隆 `.analysis/Bert-VITS2-MNN/` 中不存在 flow 导出脚本。

## 相位顺序

### P0 — 设置就绪（~3 小时）
- 创建 `.planning/opencl-perf-phase/` 结构
- 将 MNN 子模块从 v3.5.0 升级至 v3.6.0 最新标签；验证 `build_mnn.sh opencl` 链接
- 应用 `SORT_PROFILE_TIME` + GWS/LWS 补丁
- 添加独立原生基准 JNI + Android BenchmarkService

### P1 — 生成 Top‑20 剖析表（~1 小时设备时间）
- 通过 BenchmarkService 对 OPENCL_FLOW (backend=5) 执行 30 次热推理
- 对 OPENCL_DECODER (backend=2) 也执行一次
- 后处理聚合 → `name | count | total_us | avg_us | %kernel_total | gws | lws`
- 输出：`PROFILE-flow.md`、`PROFILE-decoder.md`

### P2 — 消除运行时开销（根据 P1 表）
- 消除 `materialize_to_target` 主机往返
- 常张量一次性常驻 GPU
- 形状分桶（32/64/96/128/192/256），padding 后复用会话
- `setCache` + `updateCache` 持久化 tuning

### P3 — While0 循环展开 — 新的 flow 导出器
- PyTorch 脚本从 `base_model_22k/G_0.pth` + `config.json` 中提取 flow 模块
- 以固定迭代次数（cnt∈{4,8,16}）静态展开，constant folding
- 导出 ONNX → MNNConvert → `flow_unrolled_cnt*.mnn`
- 通过 log‑mel 差异验证 ≤ -40dB

### P4 — 调参矩阵（P2/P3 开销消除后）
- `{BUFFER, IMAGE} × {WIDE, HEAVY}` 搭配缓存 tuning
- FAST 仅作为回归基线
- 每组运行 bv2RunBenchmark × 35 次热迭代；胜者成为 `AUTO`

### P5 — 自定义 OpenCL 内核（fork MNN）
- 仅 fork 前 2–3 个开销最大的算子
- half4/half8 向量化 + Adreno 750 LWS 搜索
- Snapdragon Profiler GPU trace

### P6 — 验收
- 通过 bv2RunBenchmark 加载 30 分钟稳态
- 条件：RTF ≤ 0.8×CPU, kernel_count ×5 下降, GPU/wall 差距 <10%,
  零穿插主机拷贝, 热稳态, FP16 log‑mel ≤ -40dB
- 若未达标 → REJECTION-VERIFICATION.md + 回滚至 AUTO

## 决策记录

| 分支 | 决定 |
|------|------|
| Profile 入口 | 先做 1c BenchmarkService，后做 logcat 聚合 |
| MNN 版本 | 升级至 3.6.0 最新标签 |
| While0 处理 | 编写新的 PyTorch flow 导出器 + 静态展开 |
| 文档位置 | 此 `.planning/` 结构 |
