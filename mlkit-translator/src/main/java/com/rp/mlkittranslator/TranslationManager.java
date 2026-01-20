package com.rp.mlkittranslator;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.nl.translate.Translation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TranslationManager {
    public interface AvailabilityCallback {
        void onChecked(boolean hasSource, boolean hasTarget);
    }
    public interface DownloadCallback {
        void onProgress(int percent, boolean indeterminate);
        void onCompleted();
        void onFailed();
    }
    public interface TranslateCallback {
        void onSuccess(String text);
        void onFailed();
    }

    private static volatile TranslationManager instance;

    public static TranslationManager getInstance() {
        if (instance == null) {
            synchronized (TranslationManager.class) {
                if (instance == null) {
                    instance = new TranslationManager();
                }
            }
        }
        return instance;
    }

    private Context appContext;
    private final RemoteModelManager modelManager = RemoteModelManager.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean downloading = new AtomicBoolean(false);
    private final Set<Long> activeDownloadIds = new HashSet<>();
    private Translator currentTranslator;
    private String sourceLang = TranslateLanguage.ENGLISH;
    private String targetLang = TranslateLanguage.CHINESE;
    private int retryCount = 0;
    private volatile boolean isOnline = false;

    public void initialize(Context context) {
        this.appContext = context.getApplicationContext();
        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            isOnline = hasInternet(cm.getActiveNetwork(), cm);
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    isOnline = hasInternet(network, cm);
                }
                @Override
                public void onLost(Network network) {
                    isOnline = false;
                }
            });
        } catch (Exception ignore) {}
    }

    public void setSourceLanguage(String code) {
        if (code == null || code.trim().isEmpty()) return;
        sourceLang = normalizeToMlkit(code);
        releaseTranslator();
    }

    public void setTargetLanguage(String code) {
        if (code == null || code.trim().isEmpty()) return;
        targetLang = normalizeToMlkit(code);
        releaseTranslator();
    }

    public void checkModelAvailability(AvailabilityCallback callback) {
        TranslateRemoteModel srcModel = buildModelFor(sourceLang);
        TranslateRemoteModel tgtModel = buildModelFor(targetLang);
        modelManager.getDownloadedModels(TranslateRemoteModel.class)
                .addOnSuccessListener(models -> {
                    boolean hasSrc = sourceLang.equals(TranslateLanguage.ENGLISH);
                    boolean hasTgt = targetLang.equals(TranslateLanguage.ENGLISH);
                    for (TranslateRemoteModel m : models) {
                        if (srcModel != null && m.getLanguage().equals(sourceLang)) hasSrc = true;
                        if (tgtModel != null && m.getLanguage().equals(targetLang)) hasTgt = true;
                    }
                    if (callback != null) callback.onChecked(hasSrc, hasTgt);
                })
                .addOnFailureListener(e -> {
                    if (callback != null) callback.onChecked(false, false);
                });
    }

    public void downloadModel(boolean requireWifi, DownloadCallback callback) {
        if (!isOnline) {
            if (callback != null) callback.onFailed();
            return;
        }
        List<TranslateRemoteModel> toDownload = new ArrayList<>();
        TranslateRemoteModel srcModel = buildModelFor(sourceLang);
        TranslateRemoteModel tgtModel = buildModelFor(targetLang);
        if (srcModel != null) toDownload.add(srcModel);
        if (tgtModel != null) toDownload.add(tgtModel);
        if (toDownload.isEmpty()) {
            if (callback != null) callback.onCompleted();
            return;
        }
        DownloadConditions.Builder builder = new DownloadConditions.Builder();
        if (requireWifi) {
            builder.requireWifi();
        }
        DownloadConditions conditions = builder.build();
        downloading.set(true);
        if (callback != null) callback.onProgress(0, true);
        for (TranslateRemoteModel m : toDownload) {
            modelManager.download(m, conditions)
                    .addOnSuccessListener(aVoid -> {
                        downloading.set(false);
                        if (callback != null) {
                            callback.onProgress(100, false);
                            callback.onCompleted();
                        }
                    })
                    .addOnFailureListener(e -> {
                        downloading.set(false);
                        if (callback != null) callback.onFailed();
                    });
        }
        trackDownloadProgressApprox(callback);
    }

    public void translate(String text, TranslateCallback callback) {
        if (text == null || text.trim().isEmpty()) {
            if (callback != null) callback.onFailed();
            return;
        }
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build();
        currentTranslator = Translation.getClient(options);
        currentTranslator.downloadModelIfNeeded()
                .addOnSuccessListener(aVoid -> currentTranslator.translate(text)
                        .addOnSuccessListener(t -> {
                            retryCount = 0;
                            if (callback != null) callback.onSuccess(t);
                        })
                        .addOnFailureListener(e -> handleTranslateFailure(text, callback)))
                .addOnFailureListener(e -> handleTranslateFailure(text, callback));
    }

    public void release() {
        releaseTranslator();
    }

    private void handleTranslateFailure(String text, TranslateCallback callback) {
        if (retryCount < 2) {
            retryCount++;
            handler.postDelayed(() -> translate(text, callback), 600L * retryCount);
        } else {
            retryCount = 0;
            if (callback != null) callback.onFailed();
        }
    }

    private void trackDownloadProgressApprox(DownloadCallback callback) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!downloading.get()) return;
                DownloadManager dm = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
                DownloadManager.Query q = new DownloadManager.Query();
                Cursor c = dm.query(q);
                long total = 0;
                long sofar = 0;
                activeDownloadIds.clear();
                if (c != null) {
                    while (c.moveToNext()) {
                        long id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                        String uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI));
                        String mediaType = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
                        if (mediaType != null && mediaType.contains("zip") && uri != null && uri.contains("translate")) {
                            long t = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                            long s = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                            if (t > 0) {
                                total += t;
                                sofar += s;
                                activeDownloadIds.add(id);
                            }
                        }
                    }
                    c.close();
                }
                if (callback != null) {
                    if (total > 0) {
                        int percent = (int) Math.max(0, Math.min(100, (sofar * 100 / total)));
                        callback.onProgress(percent, false);
                    } else {
                        callback.onProgress(0, true);
                    }
                }
                handler.postDelayed(this, 300);
            }
        });
    }

    private void releaseTranslator() {
        if (currentTranslator != null) {
            currentTranslator.close();
            currentTranslator = null;
        }
    }

    private TranslateRemoteModel buildModelFor(String code) {
        if (code == null || code.trim().isEmpty()) return null;
        if (TranslateLanguage.ENGLISH.equals(code)) return null;
        return new TranslateRemoteModel.Builder(code).build();
    }

    public boolean isOnline() {
        return isOnline;
    }

    private boolean hasInternet(Network n, ConnectivityManager cm) {
        if (n == null) return false;
        try {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
    }

    private String normalizeToMlkit(String code) {
        String c = code == null ? "" : code.trim().toLowerCase();
        if (c.isEmpty()) return TranslateLanguage.ENGLISH;
        if (c.startsWith("en")) return TranslateLanguage.ENGLISH;
        if (c.startsWith("zh") || c.startsWith("yue")) return TranslateLanguage.CHINESE;
        if (c.startsWith("ja")) return TranslateLanguage.JAPANESE;
        if (c.startsWith("ko")) return TranslateLanguage.KOREAN;
        return c;
    }
}
