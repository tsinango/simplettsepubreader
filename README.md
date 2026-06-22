# Simple TTS Reader

一款面向 Android 12+ 的原生 TTS 阅读器，支持 EPUB 导入朗读、离线 VITS 语音合成、句子级续读保证。

## 构建

1. 使用 Android Studio 打开项目。
2. 安装 Android SDK 35 和 JDK 17。
3. 同步 Gradle 后运行 `app`。

首次朗读时允许通知权限。

## 固定签名

为了让新 APK 可以覆盖安装旧 APK，本地构建和 GitHub Actions 构建必须使用同一个 keystore。

本地在 `local.properties` 增加：

```properties
TTS_READER_KEYSTORE=signing/tts-reader.jks
TTS_READER_KEYSTORE_PASSWORD=your-store-password
TTS_READER_KEY_ALIAS=your-key-alias
TTS_READER_KEY_PASSWORD=your-key-password
```

GitHub Actions 需要配置这些 repository secrets：

```text
TTS_READER_KEYSTORE_BASE64
TTS_READER_KEYSTORE_PASSWORD
TTS_READER_KEY_ALIAS
TTS_READER_KEY_PASSWORD
```

其中 `TTS_READER_KEYSTORE_BASE64` 是 keystore 文件的 Base64 内容。

## 续读保证

每句话交给 TTS 引擎之前，应用会先把章节、段落、句子和文本摘要写入 Room。进程中断后会从该句开头恢复，最多重复一句，不会跳过尚未确认读完的内容。

## 内置 TTS 引擎

应用支持三种 TTS 引擎：

### 系统 TTS

调用 Android 系统 TextToSpeech API，需要设备安装对应语言语音包。

### sherpa-onnx VITS 离线合成

按需下载 sherpa-onnx CPU VITS 模型，本地离线合成语音。模型不打包进 APK，首次使用在设置中下载，SHA-256 校验，支持断点续传、失败重试和删除。

| 模型 | 来源 | 体积 | 说话人 | 许可证 |
| --- | --- | --- | --- | --- |
| 内置 VITS（WNJ） | `csukuangfj/vits-zh-hf-fanchen-wnj` | ~124 MB | 中文男声 | Apache-2.0 |
| MeloTTS 中英双语 | `csukuangfj/vits-melo-tts-zh_en` | ~170 MB | 中英双语女声 | MIT |

### Bert-VITS2-MNN 离线合成

基于 [Voine/Bert-VITS2-MNN](https://github.com/Voine/Bert-VITS2-MNN) v2.0.0，使用阿里 MNN 推理框架，端侧离线推理。支持多中文说话人选择（预设角色来自原神、明日方舟等公开语音集训练）。

| 属性 | 值 |
| --- | --- |
| 采样率 | 22050 Hz |
| 模型体积 | ~140 MB |
| 语言 | 中文（国语拼音输入） |
| 说话人 | 多角色可选（陈、珐露珊、甘雨等） |
| 许可证 | 代码 Apache-2.0；模型数据仅供学习交流，禁止商用 |

从 GitHub Release 下载模型包，SHA-256 校验后自动解压使用。切换模型时安全释放旧实例。
