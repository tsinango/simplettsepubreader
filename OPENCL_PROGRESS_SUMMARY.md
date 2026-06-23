# BV2 OpenCL 推理加速 — 阶段总结 & 交接文档

提交日期: 2026-06-23 20:15
提交 SHA: 工作树尚未提交（执行 `git add` + `git commit` 后更新）
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
| **OPENCL_FLOW** | **5** | CPU 前处理 + Flow 在 GPU、Decoder 在 CPU |

### 2.2 关键代码改动（in `app/src/main/java/com/example/`）

| 文件 | 改动 |
|------|------|
| `..epubreader.tts.Bv2BenchmarkService.kt` | **新建** — 通过 ADB Intent 调用的基准测试前台服务 |
| `..bertvits2.BertVITS2JNI.kt` | 新增 `bv2RunBenchmark(...)` JNI 接口 |
| `..epubreader.tts.ReaderTtsService.kt` | 创建 BV2 引擎时硬编码 `OPENCL_FLOW`（**目前是实验性默认，不可发布**）|
| `..tts.engine.BertVits2Backend.kt` | 新增 `OPENCL_FLOW(5)` 枚举 |
| `..tts.engine.BertVits2MnnEngine.kt` | Phase 2 JNI 错误加固（移除静默 UnsatisfiedLinkError 回退）|
| `..tts.VitsModelManager.kt` | 新增 `.extracted` 标记文件检查 |
| `..tts.BertVits2MnnModelRegistry.kt` | 新增 `extractedRequiredPaths` 模型文件校验列表 |
| `app/AndroidManifest.xml` | 添加 `<uses-native-library libOpenCL.so>` + `Bv2BenchmarkService` 注册 |

### 2.3 C++ 改动（in `.analysis/Bert-VITS2-MNN/bertvits2-jni/src/main/cpp/`）

| 文件 | 改动 |
|------|------|
| `bertvits2_jni.cpp` | 新增 `bv2RunBenchmark` JNI 导出 — 在 C++ 侧循环 `warmup+bench` 次 `start_audio_infer` |
| | 新增 `setOpenclCachePath` JNI 导出 — 持久化调优缓存路径 |
| `bert_vits2_v23_loader.cpp` | `kOpenClGpuMode = MNN_GPU_TUNING_WIDE \| MNN_GPU_MEMORY_BUFFER` (原来是 FAST) |
| | 新增 `set_opencl_cache_path()` + `g_opencl_cache_path` 静态变量 |
| | `make_runtime()` 中新增 `rt->setCache(path)` — OpenCL Runtime 启动时加载缓存 |
| | `start_audio_infer()` 尾部新增 `g_rt_opencl->updateCache()` — 每次推理后保存调优数据 |
| | **OPENCL_FLOW 模式改进**: 移除 `materialize_to_target` GPU→CPU 往返 |
| | z_gpu 不再下载到 CPU，直接传递给 GPU 上的 decoder |
| | g 也在 GPU 上复用（从 flow 阶段保存到 decoder 阶段） |
| `bert_vits2_v23_loader.hpp` | `OPENCL_FLOW = 5` 枚举声明 |
| | `set_opencl_cache_path(const std::string& path)` 声明 |

### 2.4 Python 导出脚本

`scripts/export_flow_unrolled.py` — 将 BV2 flow 模块的 While 循环静态展开为
固定迭代次数的 ONNX 图。支持 cnt=4/8/16。转换逻辑：
1. 从 `base_model_22k/G_0.pth` + `base_model_22k/config.json` 加载模型
2. 实例化 `Flow(cnt)` 替代动态 `for _ in range(n_flows)`
3. `nn.ModuleList` 展开 → 导出 ONNX → MNNConvert → `.mnn`
4. 后续注册 `OPENCL_FLOW_UNROLLED = 7` 后端

### 2.5 MNN 改动

- 升级至 3.6.0 后因 BV2 flow 模型首次推理挂死（11 分钟无输出），已**回退至 3.5.0 (commit `8d9f613`)**
- 在 `source/backend/opencl/CMakeLists.txt` 添加 `add_definitions(-DSORT_PROFILE_TIME)`，使 `printEventTime()` 按耗时排序打印 kernel 事件
- 构建命令: `BuildMnnOpencl35/` 目录下 `libMNN.so` (5,569,656 bytes)
- 构建配置: `MNN_OPENCL=ON, MNN_ARM82=ON, MNN_NNAPI=ON, MNN_SEP_BUILD=OFF, MNN_GPU_TIME_PROFILE=ON`

### 2.5 基准测试工具

**ADB 调用方法**（手机连接后）:
```powershell
adb shell am start-foreground-service `
    --ei backend 5 --ei warmupIters 1 --ei benchIters 3 --ei spkid 1 `
    -n com.example.epubreader.debug.opencltest/com.example.epubreader.tts.Bv2BenchmarkService
# 等待 60-120 秒后
adb logcat -d -v threadtime MNNJNI:I '*:S' | Select-String 'kernel time ='
```

### 2.6 计划文档

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
| CPU 基线 (b22ef32) | 2,000–6,000 | 9,000–10,000 | ~9,000–12,000 | **~0.28** | — | 参考值 |
| OPENCL_ALL (image) | — | — | crash | — | — | CL_INVALID_MEM_OBJECT |
| OPENCL_DECODER (image) | ~3,000 | 43,000–54,000 | ~43,000 | 0.92 | ~3.3× 更慢 |  |
| OPENCL_ALL (buffer) | — | — | crash | — | — | DP null tensor SIGSEGV |
| OPENCL_DECODER (buffer) | ~3,000 | 42,300 | ~62,000 | 1.46 | ~5× 更慢 |  |
| OPENCL_FLOW (buffer, FAST) | **3,536** | 13,499 | **17,254** | **0.530** | **~1.9× 更慢** | Flow GPU 持平，Decoder 降频 |
| OPENCL_FLOW (MNN 3.6.0) | — | — | hang 11min+ | — | — | MNN 3.6.0 回归 |

### 3.2 Per-kernel 剖析（OPENCL_FLOW, 3.5.0）

Flow 模块的典型 kernel 时间分布（取自 15:43 logcat）:

| Kernel | 典型耗时 | 调用约次数 | 特征 |
|--------|---------|-----------|------|
| `While0` | ~8,000 us | ~100 | **主瓶颈** — 循环控制流 |
| `Raster0/1` | ~1,600–8,000 us | ~60 | 内部栅格操作 |
| `ConvBuf2D-ori` | ~8,500 us | ~30 | Conv 1D 转换 |
| `ConvBuf2D-conv1x1` | ~760 us | ~60 | 1×1 卷积 |
| `ConvBuf2D-gemm2` | ~4,500 us | ~30 | GEMM |
| `Softmax0` | ~630 us | ~30 | Softmax |
| `BinaryOp0` | ~70–960 us | ~60 | 逐元素操作 |
| `LayerNorm0` | ~70 us | ~30 | 层归一化 |

GPU kernel 总时间 ≈ 133,000 us (133ms)，但 flow wall time = 3,536ms。**GPU 时间与 wall time 差距 ~26 倍**，表明 GPU 利用率极低。

---

## 四、阶段性结论

### 4.1 MNN 的 OpenCL 后端对 BV2 存在结构性限制

1. **While0 循环不可约**：Flow 模块的 WaveNet 残差循环被 MNN 编译为 While0 算子，每次迭代独立 kernel launch。Adreno 750 的 OpenCL 调度器无法融合循环。

2. **Decoder GPU 路径异常退化**：MNN 的 GPU decoder 输出大量 Raster kernel（非 GEMM），说明模型中的某些算子没有 OpenCL 实现，回退到 CPU→GPU→CPU 的跨后端栅格拷贝。

3. **MNN 3.6.0 引入回归**：OpenCLGemmTune 新代码导致 BV2 flow 首次推理完全挂死。

### 4.2 OpenCL 加速的可行性

**流经分析的结论：直接通过 MNN 黑盒调参（切换 BUFFER/IMAGE/WIDE/HEAVY）无法解决结构性问题。**

最可能成功的路径：
1. **Flow 模块**：静态展开 While 循环（修改 PyTorch 导出脚本 `base_model_22k/G_0.pth` → ONNX → MNNConvert）
2. **Decoder 模块**：**定制 OpenCL kernel** — 针对 WN 残差块写融合 kernel，由一次 dispatch 替代 N 次
3. **形状分桶**：padding 到固定长度（32/64/96/128/192/256），消除 resize 成本

### 4.3 风险提示

- Decoder 定制 kernel 需要 fork MNN 的 OpenCL 后端，修改 `source/backend/opencl/execution/`
- 模型导出脚本需要 PyTorch 环境（`base_model_22k/G_0.pth` + `config.json` 已在 `.analysis/` 下）
- 即使成功，FP16 精度需要通过 log-mel 差异和 A/B 听感验证

---

## 五、后续推荐计划

### P0 — 恢复 ADB 测试环境（~30 分钟）

```powershell
# 复制 MNN 3.5.0 + SORT_PROFILE_TIME 构建
Copy-Item .analysis\BuildMnnOpencl35\libMNN.so app\src\main\jniLibs\arm64-v8a\libMNN.so -Force

# 确认 kOpenClGpuMode 为 MNN_GPU_TUNING_WIDE (第2轮调参)
# 编辑 bert_vits2_v23_loader.cpp:37 将 FAST 改为 WIDE

# 构建 sidecar APK
$p='app\build.gradle.kts'; $bak="$p.bak"; Copy-Item $p $bak
(Get-Content $p -Raw) -Replace('.debug"','.debug.opencltest"') | Set-Content $p -NoNewline
.\gradlew.bat assembleDebug
Copy-Item app\build\outputs\apk\debug\app-debug.apk .analysis\app-opencltest.apk
Copy-Item $bak $p; Remove-Item $bak

# 安装 & 运行
adb install -r -g .analysis\app-opencltest.apk
adb logcat -c
adb shell am start-foreground-service --ei backend 5 --ei warmupIters 1 --ei benchIters 3 --ei spkid 1 -n com.example.epubreader.debug.opencltest/com.example.epubreader.tts.Bv2BenchmarkService

# 聚合 Top-20 kernel 表格
adb logcat -d -v threadtime MNNJNI:I '*:S' `
  | Select-String 'kernel time =' `
  | ForEach-Object { if($_ -match 'kernel time = (\d+)\s+us (\S+)'){ [PSCustomObject]@{Name=$matches[2];Us=[int]$matches[1]} } } `
  | Group-Object Name `
  | ForEach-Object { [PSCustomObject]@{Name=$_.Name;Count=$_.Count;SumUs=($_.Group.Us|Measure -Sum).Sum;AvgUs=($_.Group.Us|Measure -Average).Average} } `
  | Sort-Object SumUs -Descending `
  | Select-Object -First 20 `
  | Format-Table Name,Count,@{N='Total(ms)';E={[math]::Round($_.SumUs/1000,1)}},@{N='Avg(ms)';E={[math]::Round($_.AvgUs/1000,3)}}
```

### P1 — 消除运行时开销（~1-2 天）
- 消除 `materialize_to_target` 的 `readMap` 主机往返
- 常张量一次性常驻 GPU
- 形状分桶（32/64/96/128/192/256），padding 后复用 session
- 持久化 tuning cache（`RuntimeManager::setCache` + `updateCache`）

### P2 — Flow While 展开（~1 天）
- 编写 PyTorch 导出脚本：`scripts/export_flow_unrolled.py`
- 从 `base_model_22k/G_0.pth` + `config.json` 中提取 flow 模块
- 以固定 cnt=4/8/16 静态展开 While 循环
- 验证 log-mel 差异 ≤ -40dB
- 注册 `OPENCL_FLOW_UNROLLED = 7` 后端

### P3 — Decoder 定制 kernel（~4-6 天，可并行于 P2）
- fork `source/backend/opencl/execution/buffer/` 中 Decoder 的 MNN 算子
- 针对 WN 残差块的 conv 算子和激活函数写融合 kernel
- 使用 Snapdragon Profiler 抓 OpenCL trace
- 目标是 decoderMs 从 9-10s 降至 < 2s

### P4 — 验收
- 30 分钟稳态运行
- RTF ≤ 0.8× CPU，kernel count ×5 下降
- GPU kernel 总时间 / wall time 差距 < 10%
- FP16 输出 log-mel 差异 ≤ -40dB

---

## 六、关键文件路径索引

| 文件 | 说明 |
|------|------|
| `app/src/main/java/com/example/epubreader/tts/Bv2BenchmarkService.kt` | ADB 可调用的基准测试服务 |
| `app/src/main/java/com/example/bertvits2/BertVITS2JNI.kt` | JNI 接口（含 bv2RunBenchmark）|
| `app/src/main/java/com/example/epubreader/tts/engine/BertVits2Backend.kt` | 后端枚举定义 |
| `app/src/main/java/com/example/epubreader/tts/engine/BertVits2MnnEngine.kt` | 引擎初始化 + 后端配置 |
| `.analysis/Bert-VITS2-MNN/bertvits2-jni/src/main/cpp/bertvits2_jni.cpp` | JNI C++（含 benchmark 导出）|
| `.analysis/Bert-VITS2-MNN/bertvits2-jni/src/main/cpp/bert_vits2_v23_loader.cpp` | 核心加载器 + OPENCL_FLOW 实现 |
| `.analysis/Bert-VITS2-MNN/bertvits2-jni/src/main/cpp/bert_vits2_v23_loader.hpp` | Backend 枚举 + API 声明 |
| `.analysis/BuildMnnOpencl35/libMNN.so` | **MNN 3.5.0 + SORT_PROFILE_TIME 构建产物** |
| `.analysis/Bert-VITS2-MNN/third_party/MNN/source/backend/opencl/core/runtime/OpenCLRuntime.cpp` | MNN OpenCL runtime（kernel 事件打印处） |
| `.planning/opencl-perf-phase/PLAN.md` | 6 阶段执行计划 |
| `.planning/opencl-perf-phase/ADR-001-MNN-3-6-0.md` | MNN 升级决策 |
| `.planning/opencl-perf-phase/ADR-002-flow-export.md` | Flow 导出决策 |

---

## 七、恢复开发（在新机器上）

### A. 准备工作（一次性的）

```powershell
# 克隆或拉取最新代码
git pull origin master

# 复制 MNN 3.5.0 + SORT_PROFILE_TIME 构建（确保 .analysis/ 已存在）
Copy-Item .analysis\BuildMnnOpencl35\libMNN.so app\src\main\jniLibs\arm64-v8a\libMNN.so -Force
```

### B. 构建侧载测试 APK（无需 adb）

```powershell
# 构建原生库（会在 app\src\main\jniLibs 更新 libbertvits2.so）
$env:ANDROID_HOME='C:\Users\<user>\AppData\Local\Android\Sdk'
Push-Location .analysis\Bert-VITS2-MNN
.\gradlew.bat :bertvits2-jni:assembleRelease
$src = Get-ChildItem bertvits2-jni\build\intermediates\stripped_native_libs -Recurse -Filter libbertvits2.so | Select-Object -First 1
Copy-Item $src.FullName ..\..\app\src\main\jniLibs\arm64-v8a\libbertvits2.so -Force
Pop-Location

# 构建侧载 test APK
$p='app\build.gradle.kts'; $bak="$p.bak"; Copy-Item $p $bak
(Get-Content $p -Raw) -Replace('\.debug"','.debug.opencltest"') | Set-Content $p -NoNewline
.\gradlew.bat assembleDebug
Copy-Item app\build\outputs\apk\debug\app-debug.apk .analysis\app-opencltest.apk
Copy-Item $bak $p; Remove-Item $bak
```

### C. ADB 测试流程（需要 USB 连接 vivo 手机）

```powershell
adb devices   # 确认 10AE3S1PH2000NJ 在线

# 安装 APK（手机会弹窗确认）
adb install -r -g .analysis\app-opencltest.apk

# 运行 OPENCL_FLOW 基准测试（WIDE 调优、持久缓存、与 decoder 共享 GPU 张量）
adb logcat -c
adb shell am start-foreground-service `
    --ei backend 5 --ei warmupIters 1 --ei benchIters 5 --ei spkid 1 `
    -n com.example.epubreader.debug.opencltest/com.example.epubreader.tts.Bv2BenchmarkService

# 等待约 120 秒后，聚合 Top-20 kernel 表
$log = adb logcat -d -v threadtime MNNJNI:I '*:S'
$log | Select-String 'kernel time =' `
  | ForEach-Object { if($_ -match 'kernel time = (\d+)\s+us (\S+)'){ [PSCustomObject]@{Name=$matches[2];Us=[int]$matches[1]} } } `
  | Group-Object Name `
  | ForEach-Object { [PSCustomObject]@{Name=$_.Name;Count=$_.Count;SumMs=[math]::Round(($_.Group.Us|Measure -Sum).Sum/1000,1);AvgMs=[math]::Round(($_.Group.Us|Measure -Average).Average/1000,3)} } `
  | Sort-Object SumMs -Descending `
  | Select-Object -First 20 `
  | Format-Table Name,Count,SumMs,AvgMs
```

### D. 之前 vs 现在的代码状态

| 项目 | 之前 (dee4d15) | 现在 |
|------|----------------|------|
| 调优模式 | `MNN_GPU_TUNING_FAST` | `MNN_GPU_TUNING_WIDE` |
| OpenCL 缓存 (setCache) | 无 | ✅ `setOpenclCachePath` JNI |
| updateCache() 持久化 | 无 | ✅ 每次推理后自动 |
| GPU→CPU 往返 (materialize) | 每次 flow 都有 | ✅ 消除，z 和 g 直接传递 |
| Decoder 位置 | CPU (OPENCL_FLOW) | ✅ GPU (复用 flow 的 GPU 张量) |
| Flow 导出脚本 | 无 | ✅ `scripts/export_flow_unrolled.py` |

### E. 需要真机验证的假设（按优先级排序）

| 假设 | 预期 | 验证方式 |
|------|------|---------|
| WIDE 调优降低 While0 kernel 时间 | < 6ms/次 | 比较 FAST 与 WIDE 的 kernel time 均值 |
| 全 GPU decoder 路径比 CPU 快（消除了往返） | decoderMs < 10000 | 新 OPENCL_FLOW 路径的 BV2_INFER 行 |
| setCache 持久化使第二次启动更快 | >30% 改善 | 运行两次基准，对比首次/后续 BV2_INFER totalMs |
| Flow unrolled 消除 While0 | kernel count 降 >50% | 运行展开 .mnn 后聚合 kernel 数与当前对比 |

### F. 已知风险

1. **GPU decoder 路径可能仍然慢**：OPENCL_DECODER 测试显示 decoder 在 GPU 上比 CPU 慢 5x（IMAGE + FAST）。现在使用 BUFFER + WIDE + 无往返，理论应该更好。
2. **`setOpenclCachePath` 必须在 `initBertVITS2Loader` 前调用**：已在接口中声明。
3. **WIDE 调优的首次 kernel 编译时间**：会增加首次推理约 30-60 秒，后续调用受益于 `setCache` 持久化。
4. **`.analysis/` 不提交 git**：已加入 `.gitignore`，包含 ~500MB 构建产物和 MNN 源码。新机器上需要 `git pull` 后用 `build_mnn.sh opencl` 重建 MNN，或复制已构建的 libMNN.so。

### G. 关键文件路径索引

| 文件 | 说明 |
|------|------|
| `app/src/main/java/.../Bv2BenchmarkService.kt` | ADB 基准服务（send intent 触发） |
| `app/src/main/java/.../bertvits2/BertVITS2JNI.kt` | JNI 接口（含 bv2RunBenchmark, setOpenclCachePath）|
| `.analysis/Bert-VITS2-MNN/bertvits2-jni/src/main/cpp/bertvits2_jni.cpp` | JNI C++（benchmark + cache 导出）|
| `.analysis/Bert-VITS2-MNN/bertvits2-jni/src/main/cpp/bert_vits2_v23_loader.cpp` | 核心加载器（WIDE, setCache, updateCache, GPU decoder）|
| `.analysis/Bert-VITS2-MNN/bertvits2-jni/src/main/cpp/bert_vits2_v23_loader.hpp` | Backend 枚举 + API 声明 |
| `.analysis/BuildMnnOpencl35/libMNN.so` | **MNN 3.5.0 + SORT_PROFILE_TIME** |
| `scripts/export_flow_unrolled.py` | Flow 模块 While 展开导出脚本 |

### H. 提交日志（截止当前）

- `a0b382e` feat(tts): Phase 2 MNN OpenCL backend for BV2
- `dee4d15` feat(tts): Phase 3 BV2 OpenCL profiling + benchmark harness **(需要加上新提交)**
