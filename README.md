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
