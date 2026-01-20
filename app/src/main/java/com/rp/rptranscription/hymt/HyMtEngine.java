package com.rp.rptranscription.hymt;

import android.util.Log;

import com.rp.rptranscription.lang.LanguageMapper;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HyMtEngine {

    private static final String TAG = "HyMtEngine";

    public static final String MODEL_DIR = "/data/model/assets/llama";
    public static final String MODEL_FILE = "hunyuan-1.8b-q4_0.gguf";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile long handle = 0;
    private int nThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
    private int nCtx = 512;

    public boolean init() {
        String modelPath = MODEL_DIR + File.separator + MODEL_FILE;
        long h = HyMtNative.nativeCreate(modelPath, nThreads, nCtx);
        if (h == 0L) {
            Log.e(TAG, "Failed to create engine. modelPath=" + modelPath);
            return false;
        }
        handle = h;
        Log.i(TAG, "Engine initialized. modelPath=" + modelPath + ", nThreads=" + nThreads + ", nCtx=" + nCtx);
        return true;
    }

    public boolean isReady() {
        return handle != 0L;
    }

    public void setNThreads(int nThreads) {
        this.nThreads = nThreads;
    }

    public void setNCtx(int nCtx) {
        this.nCtx = nCtx;
    }

    public interface Callback {
        void onResult(String translated);
        void onError(String message);
    }

    public void translateAsync(String sourceText, String targetLangCode, Callback callback) {
        long h = handle;
        if (h == 0L) {
            if (callback != null) {
                callback.onError("Engine not initialized");
            }
            return;
        }
        String prompt = buildPrompt(sourceText, targetLangCode);
        executor.execute(() -> {
            try {
                Log.i(TAG, "translate begin. targetLangCode=" + targetLangCode + ", srcLen=" + (sourceText == null ? 0 : sourceText.length()) + ", promptLen=" + prompt.length());
                String out = HyMtNative.nativeTranslate(h, prompt);
                String normalized = out == null ? "" : out.trim();
                Log.i(TAG, "translate end. outLen=" + (out == null ? -1 : out.length()) + ", normalizedLen=" + normalized.length());
                if (callback != null) {
                    if (normalized.isEmpty()) {
                        callback.onError("empty output");
                    } else {
                        callback.onResult(normalized);
                    }
                }
            } catch (Throwable t) {
                if (callback != null) {
                    callback.onError(t.getMessage() == null ? "translate failed" : t.getMessage());
                }
            }
        });
    }

    private static String buildPrompt(String text, String targetLangCode) {
        String code = (targetLangCode == null || targetLangCode.trim().isEmpty()) ? "en" : targetLangCode.trim();
        String label = LanguageMapper.toLabel(code);
        String src = text == null ? "" : text.trim();
        String system = "Translate strictly.";
        String extra = code.equals("yue") ? " in Cantonese using Traditional Chinese characters" : "";
        String user = "Translate the following text to " + label + extra + ". Output only the translated text. Do not use any other language.\n" + src;
        return "<｜hy_begin▁of▁sentence｜>" + system + "<｜hy_place▁holder▁no▁3｜>" +
                "<｜hy_User｜>" + user +
                "<｜hy_Assistant｜>";
    }

    public void release() {
        long h = handle;
        handle = 0L;
        if (h != 0L) {
            HyMtNative.nativeRelease(h);
        }
        executor.shutdownNow();
    }
}
