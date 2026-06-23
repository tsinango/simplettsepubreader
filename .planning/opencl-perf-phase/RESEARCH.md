# 研究结果

## MNN 3.6.0（待确认）

尚未退出计划模式 — 在执行步骤 P0b 时，将从 `git ls-remote` 解析 MNN 标签，
并记录在以下位置。待填充内容将在以下位置记录：

- 3.6.0 更新日志中与 OpenCL GEMM/GEMV 启发式相关的条目
- 与 3.5.0 的编译时差异（CMake 选项弃用、移动的代码等）
- 链接测试结果

## 相关 MNN 代码路径

| 功能 | MNN 源路径 |
|------|------------|
| OpenCL 参数管理器 + 调优缓存 | `source/backend/opencl/core/runtime/OpenCLRuntime.cpp` |
| 内核性能分析打印 | `OpenCLRuntime.cpp:1087` |
| 调优缓存持久化 | `OpenCLRuntime::setCache` + `makeCache` (`OpenCLRuntime.cpp:766,848`) |
| runtime 后的 `setCache(string)` API | `include/MNN/expr/Executor.hpp:113` |
| session 持久化 + resize | `source/core/Session.cpp` `ModeGroup::setExternalPath` |
| ComputeBackend 的 GPU 模式调度 | `source/backend/opencl/core/OpenCLBackend.cpp`、`CLRuntime::onReset` |
| 跨执行器的张量同步 | `source/backend/opencl/core/OpenCLRunningUtils.cpp`（getTunedInfo、setTunedInfo） |

## 基准测试结构设计笔记

待添加的 JNI：
- `Java_com_example_bertvits2_BertVITS2JNI_bv2RunBenchmark`（分析克隆中新的 JNI 导出）
- `Java_com_example_bertvits2_BertVITS2JNI_setOpenCLCachePath`（缓存持久化所需的路径）

新的 Kotlin：
- `BertVits2BenchmarkService.kt`：仅通过 ADB Intent extra 运行基准测试的轻量级前台服务
- 在 `AndroidManifest.xml` 边车中注册（`.debug.opencltest` applicationId）
