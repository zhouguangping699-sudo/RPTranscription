#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "HyMtJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *   model = nullptr;
    llama_context * ctx   = nullptr;
    llama_sampler * smpl  = nullptr;
    llama_batch     batch = {};
    const llama_vocab * vocab = nullptr;

    int32_t n_ctx = 1024;
    int32_t n_threads = 4;
    int32_t n_batch = 512;

    std::mutex mtx;
};

static std::string jstringToString(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

static void clear_context(Engine * e) {
    if (!e || !e->ctx) return;
    // Clear KV cache + internal buffers
    llama_memory_clear(llama_get_memory(e->ctx), true);
}

static std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special = true) {
    const int32_t n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, add_special, true);
    if (n <= 0) return {};
    std::vector<llama_token> tokens(n);
    const int32_t r = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), (int32_t) tokens.size(), add_special, true);
    if (r < 0) return {};
    return tokens;
}

static void log_model_meta_if_present(const llama_model * model, const char * key) {
    if (!model || !key) return;
    char buf[1024];
    buf[0] = '\0';
    const int32_t n = llama_model_meta_val_str(model, key, buf, sizeof(buf));
    if (n > 0) {
        LOGI("meta[%s]=%s", key, buf);
    }
}

static void log_model_metadata_brief(const llama_model * model) {
    if (!model) return;

    const int32_t cnt = llama_model_meta_count(model);
    LOGI("model meta count=%d", (int) cnt);

    // Log a few common keys (if present) to help identify prompt / template conventions.
    log_model_meta_if_present(model, "general.name");
    log_model_meta_if_present(model, "general.architecture");
    log_model_meta_if_present(model, "general.type");
    log_model_meta_if_present(model, "tokenizer.ggml.model");
    log_model_meta_if_present(model, "tokenizer.ggml.pre");
    log_model_meta_if_present(model, "tokenizer.chat_template");

    // Also log the first few metadata entries for inspection (keep it bounded).
    const int32_t max_dump = std::min<int32_t>(cnt, 48);
    for (int32_t i = 0; i < max_dump; i++) {
        char k[256];
        char v[768];
        k[0] = '\0';
        v[0] = '\0';
        const int32_t kn = llama_model_meta_key_by_index(model, i, k, sizeof(k));
        const int32_t vn = llama_model_meta_val_str_by_index(model, i, v, sizeof(v));
        if (kn > 0 && vn > 0) {
            LOGI("meta[%d] %s=%s", (int) i, k, v);
        }
    }
}

static void sampler_init(Engine * e) {
    if (!e) return;
    if (e->smpl) {
        llama_sampler_free(e->smpl);
        e->smpl = nullptr;
    }
    auto sparams = llama_sampler_chain_default_params();
    e->smpl = llama_sampler_chain_init(sparams);

    llama_sampler_chain_add(e->smpl, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(e->smpl, llama_sampler_init_top_p(0.85f, 1));
    llama_sampler_chain_add(e->smpl, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(e->smpl, llama_sampler_init_penalties(32, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(e->smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
}

static std::string token_to_piece(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    const int32_t n = llama_token_to_piece(vocab, tok, buf, (int32_t) sizeof(buf), 0, false);
    if (n <= 0) return {};
    return std::string(buf, buf + n);
}

static std::string token_to_piece_special(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    const int32_t n = llama_token_to_piece(vocab, tok, buf, (int32_t) sizeof(buf), 0, true);
    if (n <= 0) return {};
    return std::string(buf, buf + n);
}

static bool is_hy_stop_piece(const std::string & piece) {
    if (piece.empty()) return false;
    if (piece.find("hy_place") != std::string::npos) return true;
    if (piece.find("<｜") != std::string::npos) return true;
    return false;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rp_rptranscription_hymt_HyMtNative_nativeCreate(
        JNIEnv * env,
        jclass,
        jstring jModelPath,
        jint nThreads,
        jint nCtx) {

    const std::string model_path = jstringToString(env, jModelPath);
    if (model_path.empty()) {
        return 0;
    }

    auto * e = new Engine();
    e->n_threads = std::max(1, (int32_t) nThreads);
    e->n_ctx = std::max(256, (int32_t) nCtx);

    // Initialize backend once. llama.cpp tolerates multiple calls.
    ggml_backend_load_all();
    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.use_mmap = true;
    mparams.use_mlock = false;

    e->model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!e->model) {
        LOGE("Failed to load model: %s", model_path.c_str());
        delete e;
        return 0;
    }

    e->vocab = llama_model_get_vocab(e->model);

    LOGI("Model loaded. path=%s has_encoder=%d", model_path.c_str(), (int) llama_model_has_encoder(e->model));
    log_model_metadata_brief(e->model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) e->n_ctx;
    cparams.n_batch = (uint32_t) std::min(e->n_ctx, e->n_batch);
    cparams.n_ubatch = cparams.n_batch;
    cparams.n_threads = e->n_threads;
    cparams.n_threads_batch = e->n_threads;

    e->ctx = llama_init_from_model(e->model, cparams);
    if (!e->ctx) {
        LOGE("Failed to init context");
        llama_model_free(e->model);
        delete e;
        return 0;
    }

    // We use llama_batch_get_one() for simplicity.
    sampler_init(e);

    LOGI("Created engine. model=%p ctx=%p threads=%d n_ctx=%d", e->model, e->ctx, e->n_threads, e->n_ctx);

    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rp_rptranscription_hymt_HyMtNative_nativeRelease(
        JNIEnv *,
        jclass,
        jlong handle) {

    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return;

    std::lock_guard<std::mutex> lock(e->mtx);

    if (e->smpl) {
        llama_sampler_free(e->smpl);
        e->smpl = nullptr;
    }

    if (e->ctx) {
        llama_free(e->ctx);
        e->ctx = nullptr;
    }

    if (e->model) {
        llama_model_free(e->model);
        e->model = nullptr;
    }

    delete e;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rp_rptranscription_hymt_HyMtNative_nativeTranslate(
        JNIEnv * env,
        jclass,
        jlong handle,
        jstring jText) {

    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e || !e->ctx || !e->model || !e->vocab) {
        return env->NewStringUTF("");
    }

    const std::string input = jstringToString(env, jText);
    if (input.empty()) {
        return env->NewStringUTF("");
    }

    std::lock_guard<std::mutex> lock(e->mtx);

    // Reset context to avoid KV cache growing unbounded between requests.
    clear_context(e);
    sampler_init(e);

    // NOTE: The Java/Kotlin side is responsible for constructing the prompt.
    // This native method treats the input string as the full prompt.
    const std::string & prompt = input;

    LOGI("translate begin. prompt_len=%d has_encoder=%d", (int) prompt.size(), (int) llama_model_has_encoder(e->model));
    if (prompt.size() <= 160) {
        LOGI("prompt=%s", prompt.c_str());
    } else {
        LOGI("prompt(head160)=%s", prompt.substr(0, 160).c_str());
    }

    std::vector<llama_token> prompt_tokens = tokenize(e->vocab, prompt, false);
    if (prompt_tokens.empty()) {
        LOGE("tokenize failed (0 tokens)");
        return env->NewStringUTF("");
    }

    LOGI("prompt tokens=%d", (int) prompt_tokens.size());

    // Feed prompt
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), (int32_t) prompt_tokens.size());

    if (llama_model_has_encoder(e->model)) {
        // Encoder-decoder models: encode prompt first
        if (llama_encode(e->ctx, batch) != 0) {
            LOGE("llama_encode failed");
            return env->NewStringUTF("");
        }
        llama_token decoder_start = llama_model_decoder_start_token(e->model);
        if (decoder_start == LLAMA_TOKEN_NULL) {
            decoder_start = llama_vocab_bos(e->vocab);
        }
        batch = llama_batch_get_one(&decoder_start, 1);
    }

    std::string out;
    out.reserve(512);

    // Keep it small for first verification on device; can be tuned later.
    const int max_new_tokens = 48;
    int n_generated = 0;

    const auto t0 = std::chrono::steady_clock::now();

    for (int n_pos = 0; n_pos + batch.n_tokens < (int) prompt_tokens.size() + max_new_tokens; ) {
        const auto t_decode0 = std::chrono::steady_clock::now();
        LOGI("decode begin. n_pos=%d batch_tokens=%d", n_pos, (int) batch.n_tokens);
        if (llama_decode(e->ctx, batch) != 0) {
            LOGE("llama_decode failed");
            break;
        }
        const auto t_decode1 = std::chrono::steady_clock::now();
        const auto decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_decode1 - t_decode0).count();
        LOGI("decode end. n_pos=%d batch_tokens=%d decode_ms=%lld", n_pos, (int) batch.n_tokens, (long long) decode_ms);
        n_pos += batch.n_tokens;

        const llama_token tok = llama_sampler_sample(e->smpl, e->ctx, -1);
        llama_sampler_accept(e->smpl, tok);

        if (llama_vocab_is_eog(e->vocab, tok)) {
            if (n_generated == 0) {
                LOGE("sampled EOG immediately (no output)");
            }
            break;
        }

        const std::string piece_special = token_to_piece_special(e->vocab, tok);
        if (!piece_special.empty() && is_hy_stop_piece(piece_special)) {
            LOGI("stop on special piece: %s", piece_special.c_str());
            break;
        }

        const std::string piece = token_to_piece(e->vocab, tok);
        if (!piece.empty()) {
            out += piece;
        }

        // Early stop on sentence terminators to reduce latency (compare piece to avoid UTF-8 char literal issues)
        if (n_generated >= 16) {
            if (piece == "." || piece == "!" || piece == "?" || piece == u8"。" || piece == u8"！" || piece == u8"？") {
                LOGI("early stop on sentence terminator piece: %s", piece.c_str());
                break;
            }
        }

        // next token
        batch = llama_batch_get_one(const_cast<llama_token *>(&tok), 1);
        n_generated++;
        if (n_generated >= max_new_tokens) {
            break;
        }

        if ((n_generated % 8) == 0) {
            const auto t1 = std::chrono::steady_clock::now();
            const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
            LOGI("progress: generated=%d elapsed_ms=%lld out_len=%d", n_generated, (long long) ms, (int) out.size());
        }
    }

    LOGI("translate end. generated=%d out_len=%d", n_generated, (int) out.size());
    if (out.empty()) {
        LOGE("empty output - check prompt/template or language tags; see meta logs above");
    }

    return env->NewStringUTF(out.c_str());
}
