# 安读 EPUB Reader

面向 Android 12 及以上系统的原生 EPUB 阅读器。支持 EPUB 私有副本导入、目录与排版设置、系统 TTS 后台朗读，以及句子级可靠续读。

## 构建

1. 使用 Android Studio 打开项目。
2. 安装 Android SDK 35 和 JDK 17。
3. 同步 Gradle 后运行 `app`。

首次朗读时允许通知权限。设备还需要安装可用的系统文字转语音引擎和对应语言语音包。

## 固定签名

为了让新 APK 可以覆盖安装旧 APK，本地构建和 GitHub Actions 构建必须使用同一个 keystore。否则 Android 会提示 package conflicts with an existing package，只能卸载后重装。

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

每句话交给系统 TTS 之前，应用会先把章节、段落、句子和文本摘要写入 Room。进程中断后会从该句开头恢复，因此最多重复一句，不会跳过尚未确认读完的内容。

## 内置 VITS 离线朗读

除系统 TTS 外，应用支持按需下载 sherpa-onnx CPU VITS 模型，在设备本地离线合成语音。模型文件不打包进 APK，也不提交进仓库，首次使用时在设置里下载并做完整 SHA-256 校验，支持断点续传、失败重试和删除。两个模型各自独立存放、独立下载、独立统计 RTF，切换时安全释放旧实例。

### 可选模型

| 模型 | 来源 | 下载体积 | 说话人 | 许可证 | 限制 |
| --- | --- | --- | --- | --- | --- |
| 内置 VITS（WNJ） | `csukuangfj/vits-zh-hf-fanchen-wnj` | 约 124 MB | 中文女声，单说话人 | Apache-2.0（sherpa-onnx） | 仅支持中文 |
| MeloTTS 中英双语 | `csukuangfj/vits-melo-tts-zh_en` | 约 170 MB | 中英双语女声，单说话人 | MIT（MyShell.ai MeloTTS） | 英文仅保证词典中已有词汇；非词典词可能发音异常 |

两个模型都使用固定的 Hugging Face revision 下载并在落盘前逐文件校验大小与 SHA-256：

- WNJ revision：`75a59ed26f999226f412eb9e1dff31c86b42f082`
- MeloTTS revision：`a0d5c6a264c0ef92d70d8661d8cc502d79627cd6`

MeloTTS 运行所需文件为 `model.onnx`、`tokens.txt`、`lexicon.txt` 以及规则文件 `phone.fst`、`date.fst`、`number.fst`、`new_heteronym.fst`，均从上述 revision 的 `resolve` 地址获取。合成使用 `sid=0`，输出采样率以模型实际返回值为准（不写死 16 kHz）。

老用户升级后默认保持原有 WNJ 选择；新增的 `vitsModelId` 设置列通过 Room 迁移自动以 `FANCHEN_WNJ` 作为默认值写入，不会改变已选引擎。系统 TTS 与内置 VITS 回退路径继续可用。

