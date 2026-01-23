# 离线语音转写（ASR+翻译）移植计划

## 目标与策略
- 目标：在新项目中集成离线 ASR（本地识别+VAD）与 ML Kit 翻译，实现“上方原文、下方逐行拼接翻译”的字幕式体验。
- 策略：采用模块化迁移。ASR 以源码方式集成，翻译以独立库模块（或源码）集成，统一在应用层做 UI 与交互。

## 环境与依赖
- 依赖：
  - ML Kit 翻译：`com.google.mlkit:translate:17.0.3`（已封装在库模块）
  - Sherpa-ONNX JNI 与 ONNX Runtime（由 jniLibs 目录提供）
- 权限：
  - 录音：`RECORD_AUDIO`
  - 网络与网络状态：`INTERNET`、`ACCESS_NETWORK_STATE`（模型下载与在线检测）

## 模块与资源清单
- 翻译模块（库）
  - 逻辑封装：`TranslationManager`（模型检查、下载、翻译、联网检测、语言归一化、支持语言列表）
  - 参考：[TranslationManager.java](RPTranscription/mlkit-translator/src/main/java/com/rp/mlkittranslator/TranslationManager.java)
- ASR 模块（源码）
  - 识别管理器：管理录音、VAD、离线识别及回调  
    [VoiceRecognitionAndVadManager.java](RPTranscription/app/src/main/java/com/rp/rptranscription/utils/VoiceRecognitionAndVadManager.java)
  - VAD 封装：VAD 初始化、执行、回调分发、线程池管理  
    [VadManager.java](RPTranscription/app/src/main/java/com/rp/rptranscription/utils/VadManager.java)
  - JNI 资源：将目标 ABI 的 `.so` 文件置于 `app/src/main/jniLibs/<abi>/`（例如 `arm64-v8a`）
    - 参考说明：[README.md](RPTranscription/app/src/main/jniLibs/arm64-v8a/README.md)
  - 模型/资产：`assets/silero_vad.onnx`
- UI 与交互（示例） UI就不移植，仅参考对照
  - 主界面布局：上半区 ASR 文本、下半区翻译文本、进度与状态  
    [activity_main.xml](RPDevelopment/ASR/RPTranscription/app/src/main/res/layout/activity_main.xml)
  - 语言选择对话框与列表适配：  
    [LanguageSelectionDialog.java](RPTranscription/app/src/main/java/com/rp/rptranscription/ui/LanguageSelectionDialog.java)  
    [LanguageListAdapter.java](RPTranscription/app/src/main/java/com/rp/rptranscription/ui/LanguageListAdapter.java)
  - 主界面逻辑（触发识别与逐行翻译）：
    [MainActivity.java](app/src/main/java/com/rp/rptranscription/MainActivity.java)

## 集成步骤
1. 新项目创建翻译库模块（或复制源码）
   - 新建 `mlkit-translator` 库模块，添加依赖：  
     `implementation("com.google.mlkit:translate:17.0.3")`
   - 复制 `TranslationManager` 源码与 `AndroidManifest.xml`（声明 `ACCESS_NETWORK_STATE`）
2. 引入 ASR 源码
   - 复制 `utils` 包中的 `VoiceRecognitionAndVadManager` 与 `VadManager`
   - 复制 `assets/silero_vad.onnx` 至目标项目的 `app/src/main/assets/`
   - 复制对应 ABI 的 JNI `.so` 至 `app/src/main/jniLibs/<abi>/`
3. 权限与清单
   - 在应用清单添加：  
     `RECORD_AUDIO`、`INTERNET`、`ACCESS_NETWORK_STATE`
4. UI 与交互
   - 参考 `activity_main.xml` 搭建界面（或兼容目标项目的 UI）
   - 使用 `LanguageSelectionDialog` 显示源/目标语言选择（数据源：`TranslationManager.getSupportedLanguageCodes()`）
   - 在识别回调中：ASR 文本按行追加；仅对新增最终识别行触发翻译，并在下方逐行追加结果，保持上下行数对齐
5. 构建设置
   - 如启用混淆/资源收缩，确保 JNI 与模型资源保留
   - ABI 过滤与目标设备一致（常见为 `arm64-v8a`）

## 语言支持与选择
- 支持语言集合：使用 ML Kit 官方集合 `TranslateLanguage.getAllLanguages()`（包含 50+ 语言）
- 常见代码：en 英语、zh 中文、ja 日语、ko 韩语、he 希伯来语、hr 克罗地亚语、is 冰岛语、ms 马来语、no 挪威语等
- 归一化策略（示例已实现）：
  - `en`→英语；`zh`/`zh-*` 与 `yue`→中文；`ja`→日语；`ko`→韩语
- 官方支持语言列表参考：  
  https://developers.google.cn/ml-kit/language/translation/translation-language-support?hl=zh-cn

## 权限与网络
- 模型下载需要网络与网络状态权限；离线时优先使用已下载模型。
- 翻译模块内置网络状态监听；离线下载请求直接反馈失败并提示。

## 构建与发布注意事项
- 保证 `.so` 与设备 ABI 匹配，否则会出现加载失败。
- 资源收缩不要移除 VAD 模型文件与 JNI 依赖。
- 若开启 ProGuard/R8，谨慎保留 ML Kit 与 Sherpa-ONNX 相关类。

## 验收测试清单
- 功能：
  - 中→英、英→中、多语言对（he/hr/is/ms/no）翻译确认
  - 离线模式：已下载模型可用；未下载时提示不可用
- 稳定性：
  - 连续开始/停止录音无 `RejectedExecutionException`
  - Activity 生命周期切换后 ASR 与翻译正常
- 兼容性：
  - 不同机型与 ABI JNI 加载成功
  - 权限拒绝路径反馈明确

## 常见问题与优化
- 翻译失败：重试策略已内置（指数退避最多 2 次）；可在 UI 提示并保留行对齐
- 源/目标一致：会返回原文；可在 UI 提示更换目标语言
- 滚动联动：可选实现上下 TextView 同步滚动

## 目录精简建议
- 如不再需要本地 LLM 示例工程与 CMake 内容，可删除 `llama.cpp/` 以减小仓库体积；当前应用与库不依赖该目录。

