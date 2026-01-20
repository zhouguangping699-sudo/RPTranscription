# HY-MT1.5-1.8B（llama.cpp）Android 离线翻译集成方案（可落地版）

本文档用于在 **RPTranscription** 工程中集成 HY-MT1.5-1.8B GGUF 量化模型，技术路线为：

- `llama.cpp` 编译为 `.so`
- 通过 JNI 封装 `init / translate / release`
- Java/Kotlin 层用单线程或协程串行调用
- **模型文件不放 assets**；你将手动部署到固定路径：`/data/model/assets`

## 0. 前置条件（非常重要）

你指定的模型路径是：`/data/model/assets`。

- **普通三方 App 默认无法读取 `/data/...`**（Android 10+ 分区隔离 + SELinux）。
- 若要从该路径读取模型，需要满足至少一个条件：
  - 设备 root / 工程机环境
  - App 是 system/priv-app（平台签名、或厂商定制）并且 SELinux/权限允许读取
  - 由你的系统服务（例如你已有的 `rpSystemService`）负责把文件句柄/可读副本提供给 App

如果后续发现无法直接访问 `/data/model/assets`，最稳妥的 fallback 路径是：

- `context.getExternalFilesDir(null)`
- `context.getFilesDir()`

但本文按你的要求，代码默认指向 `/data/model/assets`。

## 1. 推荐目录结构（与现有工程兼容）

在当前 repo 内引入 `llama.cpp`（建议 submodule/复制均可）：

- `app/src/main/cpp/third_party/llama.cpp/`  
- `app/src/main/cpp/hy_mt/`（你的 JNI 封装）

> 重点：不要新建 `app/` 根目录的 `CMakeLists.txt`。
> 直接把 llama.cpp 集成到你现有的 `app/src/main/cpp/CMakeLists.txt`。

## 2. Gradle（app/build.gradle.kts）需要满足的条件

你的工程已经在用 CMake（SentencePiece 等）。这里强调 HY-MT 集成所需的约束：

- **ABI 仅 `arm64-v8a`**（大模型强烈建议）
- 确保 `externalNativeBuild.cmake` 指向现有 `app/src/main/cpp/CMakeLists.txt`
- 如需要裁剪 llama.cpp，追加 CMake arguments（示例）

```kotlin
android {
  defaultConfig {
    ndk {
      abiFilters.clear()
      abiFilters += listOf("arm64-v8a")
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      // 可选：arguments += listOf("-DLLAMA_BUILD_TESTS=OFF")
    }
  }
}
```

## 3. CMake 集成（app/src/main/cpp/CMakeLists.txt）

在你现有的 `CMakeLists.txt` 末尾（或合适位置）加入以下块即可：

```cmake
# llama.cpp
set(LLAMA_CPP_DIR ${CMAKE_CURRENT_LIST_DIR}/third_party/llama.cpp)
add_subdirectory(${LLAMA_CPP_DIR} llama_cpp_build)

add_library(hy_mt_jni SHARED
  hy_mt/hy_mt_jni.cpp
)

target_include_directories(hy_mt_jni PRIVATE
  ${LLAMA_CPP_DIR}/include
  ${LLAMA_CPP_DIR}
)

target_link_libraries(hy_mt_jni
  llama
  log
)
```

注意事项：

- llama.cpp 的 target 名称在不同版本可能是 `llama` / `llama-lib` 等。
- 以你引入的 llama.cpp 版本为准；若 target 不叫 `llama`，需要改成实际 target。

## 4. JNI 封装（可编译的最小骨架）

在 `app/src/main/cpp/hy_mt/hy_mt_jni.cpp` 创建 JNI 封装。

关键原则：

- **不要用 static 全局单例**（容易泄漏、并发难控）。用 `nativeHandle: Long` 管理。
- 翻译调用必须串行（一个 ctx 同时推理多请求会乱）。
- JNI 层必须提供 `release()`。

> llama.cpp API 变动频繁。为了让代码“可落地”，你需要把 llama.cpp 固定到某个 commit/tag。
> 下方 JNI 给的是“工程骨架”，你只需按你实际使用的 llama.cpp 版本把 API 名称对齐即可。

```cpp
#include <jni.h>
#include <string>
#include <mutex>

#include "llama.h"

struct HyMtEngine {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    std::mutex mtx;
};

static std::string jstringToString(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rp_rptranscription_hymt_HyMtNative_nativeCreate(
    JNIEnv* env, jclass /*clazz*/, jstring modelPath, jint nThreads, jint nCtx) {

    auto* engine = new HyMtEngine();

    std::string path = jstringToString(env, modelPath);

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.use_mmap = true;
    mparams.use_mlock = false;

    engine->model = llama_model_load_from_file(path.c_str(), mparams);
    if (!engine->model) {
        delete engine;
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx;
    cparams.n_threads = nThreads;

    engine->ctx = llama_new_context_with_model(engine->model, cparams);
    if (!engine->ctx) {
        llama_free_model(engine->model);
        delete engine;
        return 0;
    }

    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rp_rptranscription_hymt_HyMtNative_nativeRelease(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {

    auto* engine = reinterpret_cast<HyMtEngine*>(handle);
    if (!engine) return;
    if (engine->ctx) {
        llama_free(engine->ctx);
        engine->ctx = nullptr;
    }
    if (engine->model) {
        llama_free_model(engine->model);
        engine->model = nullptr;
    }
    delete engine;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rp_rptranscription_hymt_HyMtNative_nativeTranslate(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jstring text) {

    auto* engine = reinterpret_cast<HyMtEngine*>(handle);
    if (!engine || !engine->ctx || !engine->model) {
        return env->NewStringUTF("");
    }

    std::lock_guard<std::mutex> lock(engine->mtx);

    std::string input = jstringToString(env, text);

    // TODO: 按 HY-MT1.5 的推荐模板构造 prompt
    // 注意：翻译模型通常不是 chat 模型，模板以官方为准
    std::string prompt = input;

    // TODO: 下面的 tokenize/decode/sampling 需要按你固定的 llama.cpp 版本实现
    // 关键点：每生成一个 token，都需要继续 decode 推进上下文
    // 返回值建议做最大 tokens 限制，避免无限生成

    std::string out;
    return env->NewStringUTF(out.c_str());
}
```

上面的 `nativeTranslate()` 里我保留了 TODO：

- 你必须把 llama.cpp 固定到某个版本后，把 tokenize/decode/sampling 逻辑补齐
- 这是为了避免文档写死某个 API 结果你拉了不同版本导致“编译不过”

## 5. Kotlin 封装（代码统一指向 /data/model/assets）

目录：`app/src/main/java/com/rp/rptranscription/hymt/HyMtEngine.kt`

```kotlin
package com.rp.rptranscription.hymt

import java.io.File
import java.util.concurrent.Executors

object HyMtPaths {
  const val MODEL_DIR = "/data/model/assets"
  const val MODEL_FILE = "hunyuan-1.8b-q4_0.gguf"
  val modelPath: String get() = MODEL_DIR + File.separator + MODEL_FILE
}

internal object HyMtNative {
  init {
    System.loadLibrary("hy_mt_jni")
  }

  external fun nativeCreate(modelPath: String, nThreads: Int, nCtx: Int): Long
  external fun nativeTranslate(handle: Long, text: String): String
  external fun nativeRelease(handle: Long)
}

class HyMtEngine(
  private val nThreads: Int = 4,
  private val nCtx: Int = 1024,
) {
  private val executor = Executors.newSingleThreadExecutor()
  @Volatile private var handle: Long = 0

  fun init(): Boolean {
    val path = HyMtPaths.modelPath
    handle = HyMtNative.nativeCreate(path, nThreads, nCtx)
    return handle != 0L
  }

  fun translateAsync(text: String, callback: (String) -> Unit) {
    val h = handle
    if (h == 0L) {
      callback("")
      return
    }
    executor.execute {
      val out = HyMtNative.nativeTranslate(h, text)
      callback(out)
    }
  }

  fun release() {
    val h = handle
    handle = 0
    if (h != 0L) {
      HyMtNative.nativeRelease(h)
    }
    executor.shutdownNow()
  }
}
```

## 6. 模型部署到 /data/model/assets（你手动推进）

你计划把模型放到：

- `/data/model/assets/hunyuan-1.8b-q4_0.gguf`

需要满足：

- 文件对你的 App 进程可读（权限/SELinux）
- 存储空间充足（模型 + 预留）

建议你额外准备一个“路径可读性自检”方法（Java 层 `File(path).canRead()`）用于排障。

## 7. 性能建议（Android 端可用默认值）

- **量化**：优先 Q4（体积小、内存低），再考虑 Q5/Q6
- **n_ctx**：先从 512/1024 起，稳定后再加
- **线程数**：建议可配置；默认 4 是保守值
- **max tokens**：必须限制（例如 256/512），避免无限生成导致卡死/发热

## 8. 关键修正说明（已核对版）

- 模型文件名以你实际产物为准（本文默认 `hunyuan-1.8b-q4_0.gguf`）
- Prompt/模板必须以 HY-MT1.5 的官方推荐为准；翻译模型不一定适配 chat 模板
- 不使用 assets：模型走 `/data/model/assets/...` 真实文件路径
- 初始化/推理必须后台线程，UI 只做回调更新
- ABI 仅 arm64-v8a


/data/model/assets/
├── llama/                                            
│   ├──hunyuan-1.8b-q4_0.gguf      翻译模型
│
├── sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/ 
│   ├── export-onnx
│   ├── model.int8.onnx
│   ├── tokens.txt
│ 
└── silero_vad.onnx


Languages	Abbr.	Chinese Names
Chinese	zh	中文
English	en	英语
French	fr	法语
Portuguese	pt	葡萄牙语
Spanish	es	西班牙语
Japanese	ja	日语
Turkish	tr	土耳其语
Russian	ru	俄语
Arabic	ar	阿拉伯语
Korean	ko	韩语
Thai	th	泰语
Italian	it	意大利语
German	de	德语
Vietnamese	vi	越南语
Malay	ms	马来语
Indonesian	id	印尼语
Filipino	tl	菲律宾语
Hindi	hi	印地语
Traditional Chinese	zh-Hant	繁体中文
Polish	pl	波兰语
Czech	cs	捷克语
Dutch	nl	荷兰语
Khmer	km	高棉语
Burmese	my	缅甸语
Persian	fa	波斯语
Gujarati	gu	古吉拉特语
Urdu	ur	乌尔都语
Telugu	te	泰卢固语
Marathi	mr	马拉地语
Hebrew	he	希伯来语
Bengali	bn	孟加拉语
Tamil	ta	泰米尔语
Ukrainian	uk	乌克兰语
Tibetan	bo	藏语
Kazakh	kk	哈萨克语
Mongolian	mn	蒙古语
Uyghur	ug	维吾尔语
Cantonese	yue	粤语