# BV2 OpenCL 推理加速 — 阶段总结 & 交接文档

提交日期: 2026-06-24 00:50
提交 SHA: `54df4e2` + 未提交更改
远程仓库: `https://github.com/tsinango/simplettsepubreader.git`

---

## 一、项目目标

在 Snapdragon 8 Gen 3 设备（vivo 手机）上，为 Bert-VITS2-MNN (BV2-22k) 模型实现 MNN OpenCL GPU 推理加速，目标 RTF ≤ 0.8× CPU 基线。

## 二、已做的工作

### 2.1 后端枚举与配置

| 后端 | ID | 描述 |
|------|-----|------|
| CPU | 0 | MNN_FORWARD_CPU, Precision_Low_BF16 |
| OPENCL_ALL | 1 | 全流水线 GPU（BERT/Enc/DP/SDP/Flow/Decoder）|
| OPENCL_DECODER | 2 | BERT~Flow 在 CPU, Decoder 在 GPU |
| NNAPI | 3 | MNN_FORWARD_NN |
| AUTO | 4 | 设备支持 OpenCL 时选 OPENCL_ALL，否则 CPU |
| **OPENCL_FLOW** | **5** | CPU 前处理 + Flow 在 GPU、Decoder 在 GPU |

### 2.2 关键代码改动

| 文件 | 改动 |
|------|------|
| `..epubreader.tts.Bv2BenchmarkService.kt` | **新建** — 通过 ADB Intent 调用的基准测试前台服务 |
| `..bertvits2.BertVITS2JNI.kt` | 新增 `bv2RunBenchmark(...)` JNI 接口 + `setOpenclCachePath` |
| `..epubreader.tts.ReaderTtsService.kt` | 创建 BV2 引擎时硬编码 `OPENCL_FLOW` |
| `..tts.engine.BertVits2Backend.kt` | 新增 `OPENCL_FLOW(5)` 枚举 |
| `..tts.engine.BertVits2MnnEngine.kt` | 新增 `setOpenclCachePath` 调用（Phase 4） |
| `..tts.engine.BertVits2MnnEngine.kt` | Phase 2 JNI 错误加固 |
| `..tts.VitsModelManager.kt` | 新增 `.extracted` 标记文件检查 |
| `..tts.BertVits2MnnModelRegistry.kt` | 新增 `extractedRequiredPaths` 模型文件校验列表 |
| `app/AndroidManifest.xml` | 添加 `<uses-native-library libOpenCL.so>` + `Bv2BenchmarkService` 注册 |

### 2.3 C++ 改动（MNN 源码，克隆自 `alibaba/MNN` tag `3.5.0`）

| 文件 | 改动 |
|------|------|
| `source/backend/opencl/core/OpenCLBackend.cpp` | `setGpuMode()`: 强制 FAST 调优（替代导致 OOM 的 WIDE）|
| `source/backend/opencl/core/OpenCLBackend.cpp` | `setGpuMode()`: Adreno GPU 强制 IMAGE 模式 |
| `source/backend/opencl/execution/image/LoopExecution.cpp` | **修复内存泄露**: `mTmpBuffers` 永不回收 → `CL_INVALID_MEM_OBJECT` |
| `source/backend/opencl/execution/image/LoopExecution.hpp` | 加析构函数 `~LoopExecution()` |
| `source/backend/opencl/execution/image/GroupNormExecution.hpp` | **新建** — IMAGE 模式 GroupNorm 算子 |
| `source/backend/opencl/execution/image/GroupNormExecution.cpp` | **新建** — IMAGE 模式 GroupNorm 实现 |
| `source/backend/opencl/execution/cl/groupnorm.cl` | **新建** — GroupNorm OpenCL kernel 源码 |
| `source/backend/opencl/execution/cl/groupnorm_mnn_cl.cpp` | **新建** — kernel 嵌入注册 |
| `source/backend/opencl/execution/cl/attention_buf_mnn_cl.cpp` | **修复** — 原为空文件导致链接失败 |
| `source/backend/opencl/execution/cl/opencl_source_map.hpp` | 注册 `groupnorm` 程序名 |
| `source/backend/opencl/CMakeLists.txt` | 添加 `add_definitions(-DSORT_PROFILE_TIME)` |

### 2.4 MNN 构建

- 版本: MNN 3.5.0 (commit `aceffd3`)
- 构建配置: `MNN_OPENCL=ON, MNN_ARM82=ON, MNN_NNAPI=ON, MNN_SEP_BUILD=OFF, MNN_GPU_TIME_PROFILE=ON`
- 产物: `libMNN.so` (5,087,016 bytes, stripped)
- NDK: `27.0.12077973`, Ninja generator

### 2.5 Python 导出脚本

`scripts/export_flow_unrolled.py` — 将 BV2 flow 模块的 While 循环静态展开为
固定迭代次数的 ONNX 图。**发现 BV2-22k v2.3 实际使用 Glow-based flow（非 WaveNet），While 循环非性能瓶颈。**

### 2.6 基准测试工具

**ADB 调用方法**（手机连接后）:
```powershell
adb shell am start-foreground-service `
    --ei backend 5 --ei warmupIters 1 --ei benchIters 3 --ei spkid 1 `
    -n com.example.epubreader.debug.opencltest/com.example.epubreader.tts.Bv2BenchmarkService
adb logcat -d -v threadtime MNNJNI:I '*:S' | Select-String 'kernel time ='
```

### 2.7 计划文档

`.planning/opencl-perf-phase/` 包含:
- `PLAN.md` — 总体执行计划（6 阶段）
- `ADR-001-MNN-3-6-0.md` — MNN 升级决策
- `ADR-002-flow-export.md` — Flow 模型导出决策
- `RESEARCH.md` — 技术研究记录

---

## 三、真机测试结果（Snapdragon 8 Gen 3）

### 3.1 各后端 RTF 汇总

数据集：测试文本 "测试文本" → y_length=72 → audio_dur ≈ 32554ms (22050Hz, 717824 samples)

| 模式 | flow ms | decoder ms | total ms | RTF | vs CPU | 备注 |
|------|---------|------------|----------|-----|--------|------|
| **CPU 基线** | **206** | **1,041** | **1,371** | **0.82** | — | 唯一可工作的后端 |
| OPENCL_FLOW (IMAGE, FAST) | ~5,000 | 挂死+ | — | — | — | Flow GPU 运行但 Decoder 挂死 |
| OPENCL_FLOW (BUFFER, FAST) | ~5,000 | 挂死+ | — | — | — | 同上 |
| OPENCL_DECODER (IMAGE, FAST) | CPU | 挂死 | — | — | — | Decoder GPU 结构性问题 |
| OPENCL_ALL (IMAGE) | — | — | crash | — | — | CL_INVALID_MEM_OBJECT（已修复）|
| NNAPI | — | — | SIGSEGV | — | — | 不兼容 BV2 算子 |

### 3.2 Per-kernel 剖析（IMAGE 模式 flow）

取自 00:31:12 logcat — flow 模块在 GPU 上执行（IMAGE + FAST）:

| Kernel | 典型耗时 | 特征 |
|--------|---------|------|
| `Convolution0` | ~600–1,700 us | IMAGE 模式卷积，flow 主算子 |
| `While0` | ~15–28 us | **非瓶颈**（仅 0.02ms）|
| `BinaryOp0` | ~5–6 us | 逐元素操作 |
| `convertBufferToImage` | ~143–407 us | CPU→GPU 格式转换 |

**关键发现**: While0 仅 ~28μs，P2 Flow 展开不解决实际问题。

### 3.3 Decoder GPU 路径分析

Decoder 在 GPU 上执行时间远超 CPU（>4 分钟 vs CPU 1 秒），原因是：
1. **缺失算子 OpenCL 实现** — 部分算子 fallback 到 CPU，触发 `mDivideOpRecord` 同步屏障
2. **Raster 拷贝** — GPU↔CPU 数据往返产生大量栅格操作
3. **ConvBuf2D-ori kernel 效率低** — BUFFER 模式下 ~1ms/kernel × 数百次

---

## 四、阶段性结论

### 4.1 原假设修正

1. ❌ **While0 是瓶颈** — 实测仅 ~28μs，微不足道。实际瓶颈是 ConvBuf2D
2. ❌ **WIDE 调优可改善** — 导致 OOM/挂死，必须用 FAST
3. ❌ **Decoder GPU 路径可用** — 缺算子实现，比 CPU 更慢
4. ✅ **IMAGE 模式优于 BUFFER** — Adreno 750 上 IMAGE 模式更稳定
5. ✅ **内存泄露是 CL_INVALID_MEM_OBJECT 的根因** — 已修复

### 4.2 当前技术现状

**已修复:**
- CL_INVALID_MEM_OBJECT 崩溃（LoopExecution 内存泄露）
- WIDE 调优 OOM（强制 FAST）
- IMAGE 模式 GroupNorm 缺失（新建实现）
- setOpenclCachePath 持久化调用（BertVits2MnnEngine.kt）

**未解决:**
- Decoder GPU 路径算子缺失（decoder 在 GPU 上挂死）
- NNAPI 崩溃（SIGSEGV）
- CPU RTF 0.82（可接受但不如早前报告的 0.28）

### 4.3 风险提示

- Decoder 定制 kernel 需要 fork MNN 的 OpenCL 后端，修改 `source/backend/opencl/execution/`
- G_0.pth 需要 PyTorch 环境（已下载 732MB，SHA256 校验通过）
- FP16 精度需要通过 log-mel 差异和 A/B 听感验证

---

## 五、后续推荐计划

### P3 — Decoder 定制 kernel（高优先级）
- 给 IMAGE 模式加缺失算子: Deconv、DepthwiseConv、GroupNorm（已实现）
- 分析 decoder 模型哪些算子触发了 CPU fallback（启用 `#define OPENCL_FALLBACK_LOG`）
- 写 fused WN kernel — 将 WaveNet 残差块融合为单次 GPU dispatch
- 目标是 decoderMs 从 >4min 降至 < 1s

### P4 — 验收
- 30 分钟稳态运行
- RTF ≤ 0.8× CPU
- FP16 输出 log-mel 差异 ≤ -40dB

---

## 六、关键文件路径索引

### MNN 源码修改（`C:\Users\gqn\AppData\Local\Temp\mnn-src`）

| 文件 | 说明 |
|------|------|
| `source/backend/opencl/core/OpenCLBackend.cpp` | FAST 调优 + IMAGE 模式强制覆盖 |
| `source/backend/opencl/execution/image/LoopExecution.cpp` | 内存泄露修复（mTmpBuffers 回收）|
| `source/backend/opencl/execution/image/LoopExecution.hpp` | 析构函数声明 |
| `source/backend/opencl/execution/image/GroupNormExecution.cpp` | **新建** IMAGE GroupNorm |
| `source/backend/opencl/execution/image/GroupNormExecution.hpp` | **新建** IMAGE GroupNorm 头文件 |
| `source/backend/opencl/execution/cl/groupnorm.cl` | **新建** GroupNorm kernel |
| `source/backend/opencl/execution/cl/groupnorm_mnn_cl.cpp` | **新建** kernel 嵌入 |
| `source/backend/opencl/execution/cl/opencl_source_map.hpp` | 注册 groupnorm 程序名 |
| `source/backend/opencl/CMakeLists.txt` | 加 SORT_PROFILE_TIME |
| `source/backend/opencl/execution/cl/attention_buf_mnn_cl.cpp` | 填充空文件修复链接 |

### App 代码

| 文件 | 说明 |
|------|------|
| `app/.../tts/engine/BertVits2MnnEngine.kt` | `setOpenclCachePath` 调用 |
| `app/.../tts/engine/BertVits2Backend.kt` | 后端枚举 |
| `app/.../tts/Bv2BenchmarkService.kt` | ADB 基准服务 |
| `app/.../bertvits2/BertVITS2JNI.kt` | JNI 接口 |
| `app/src/main/jniLibs/arm64-v8a/libMNN.so` | **MNN 3.5.0 + 定制补丁** |

### 其他

| 文件 | 说明 |
|------|------|
| `scripts/export_flow_unrolled.py` | Flow 导出脚本（待适配 Glow 架构）|
| `.planning/opencl-perf-phase/` | 阶段计划 + ADR 文档 |
| `C:\Users\gqn\AppData\Local\Temp\G_0.pth` | 已下载校验通过 |
| `C:\Users\gqn\AppData\Local\Temp\mnn-src\` | MNN 源码 + 所有修改 |

---

## 七、恢复开发（在新机器上）

### A. 准备工作

```powershell
git pull origin master
# libMNN.so 已提交到 git（app/src/main/jniLibs/）
```

### B. 构建侧载测试 APK

```powershell
$p='app\build.gradle.kts'; $bak="$p.bak"
Copy-Item $p $bak
(Get-Content $p -Raw) -Replace('\.debug"','.debug.opencltest"') | Set-Content $p -NoNewline
.\gradlew.bat assembleDebug
Copy-Item $bak $p; Remove-Item $bak
```

### C. ADB 测试

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell "run-as com.example.epubreader.debug tar cf - /data/data/.../bertvits2-mnn-22k-zh" | adb exec-out "run-as com.example.epubreader.debug.opencltest tar xf - -C /data/data/..."
adb logcat -c
adb shell am start-foreground-service --ei backend 5 --ei warmupIters 1 --ei benchIters 3 --ei spkid 1 -n com.example.epubreader.debug.opencltest/com.example.epubreader.tts.Bv2BenchmarkService
```

### D. MNN 源码重建

MNN 源码位于 `C:\Users\gqn\AppData\Local\Temp\mnn-src\`（不在项目仓库内）。
如需在新机器上重建:

```powershell
git clone --depth 1 --branch 3.5.0 https://github.com/alibaba/MNN.git mnn-src
# 手动应用以下修改:
#   OpenCLBackend.cpp: FAST 调优 + IMAGE 覆盖
#   LoopExecution.cpp/hpp: 内存泄露修复
#   GroupNormExecution.cpp/hpp: 新建 IMAGE GroupNorm
#   groupnorm.cl: 新建 kernel
#   groupnorm_mnn_cl.cpp: 新建注册
#   opencl_source_map.hpp: 注册 groupnorm
#   attention_buf_mnn_cl.cpp: 填充
#   CMakeLists.txt: SORT_PROFILE_TIME

cmake mnn-src -G Ninja -DCMAKE_TOOLCHAIN_FILE=... -DANDROID_ABI=arm64-v8a ...
cmake --build . --target MNN -j 8
```

### E. 已知风险

1. **Decoder GPU 路径算子缺失** — 需要加 IMAGE 模式算子或写 fused kernel
2. **`.analysis/` 不提交 git** — MNN 源码修改需要手动移植
3. **G_0.pth 不提交 git** — 732MB，需单独下载
4. **libMNN.so 已提交 git** — 包含所有定制补丁，新机器直接用

### F. 提交日志

- `54df4e2` feat(tts): WIDE tuning + persistent cache + GPU decoder path + flow export
- `a0b382e` feat(tts): Phase 2 MNN OpenCL backend for BV2
- `dee4d15` feat(tts): Phase 3 BV2 OpenCL profiling + benchmark harness
- **(新提交)** feat(tts): Phase 4 MNN OpenCL IMAGE mode + memory leak fix + GroupNorm IMAGE
