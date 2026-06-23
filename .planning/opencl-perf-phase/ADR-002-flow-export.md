# ADR-002: 编写 PyTorch flow 导出脚本，用于 While0 循环展开

**状态**: 已通过 | **日期**: 2026-06-23 | **决策者**: tsinango

## 背景

P1 的 BV2 推理剖析显示，flow 模块的 While0 内核（~8ms × 数百次）是主要的 OpenCL 瓶颈。
这些是 WaveNet/WN 残差迭代中控制流循环的伪像。
MNN 的 OpenCL 后台不是针对此类动态循环 kernel launch 模式设计的；
GPU 每次迭代都支付完整的栅格调度+cost。

## 选项对比

| 选项 | 工作量 | 风险 | 复用性 |
|------|--------|------|--------|
| A: 编写新的 PyTorch flow 导出器 + 静态展开 | ~1 天 | 低；恒等通过 log‑mel 验证 | 可在不同迭代计数下复用，用于 perf‑质量权衡 |
| B: 定位上游已有的展开变体 | 数小时 | 中；上游可能不维护固定变体 | 单一 .mnn，难以修改 |
| C: MNN 图级融合（不触 PyTorch） | ~3–5 天 | 高；侵入性全局 MNN transform | 可能无法消除 While 循环本身 |

## 决定

**选项 A**：在 `.analysis/Bert-VITS2-MNN/scripts/export_flow_unrolled.py` 中
编写新的 PyTorch 导出脚本。

## 详细方案

1. 从 `base_model_22k/G_0.pth` + `config.json` 实例化完整 BV2 模型
2. 仅提取 `net.flow`（synthesizer downstream）子模块
3. 在 torch.export 前通过设置 `flow = Flow(cnt=4)` 为每个 cnt∈{4,8,16} 硬编码迭代次数
4. 将 ONNX 导出至 `flow_unrolled_cnt4.onnx` 等
5. 以 onnx‑simplifier 进行常量折叠（消除 Shape/Cast/Expand 循环时产生的伪像）
6. 使用 `MNNConvert -f ONNX --target MNN` 转换为 MNN
7. 使用 MNN Python backend 的相对 log‑mel 差异（≤ -40dB）验证与 PyTorch 真值的差异
8. 在 `BertVits2Backend.kt` + `bert_vits2_v23_loader.hpp` 中注册
   `OPENCL_FLOW_UNROLLED = 7`
9. 每个形状段（<64, <128, <192, <256）加载各自展开的 .mnn

## 验收标准

- 导出的 .mnn 加载时无 MNN 崩溃
- log‑mel 差异 ≤ -40dB（PyTorch FP32 参考与展开的 ONNX→MNN 路径之间）
- 在 cnt=4/8/16 上将 bv2RunBenchmark 单句 flowMs 递减（~线性伸展）
