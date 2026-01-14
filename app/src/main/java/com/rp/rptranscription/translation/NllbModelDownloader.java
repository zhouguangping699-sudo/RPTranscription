package com.rp.rptranscription.translation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class NllbModelDownloader {
    public interface Listener {
        void onProgress(int percent, String currentFileName);
        void onCompleted(File modelDir);
        void onFailure(Exception e);
    }

    private static final String BASE_URL = "https://github.com/niedev/RTranslator/releases/download/2.0.0/";
    public static final String FILE_CACHE_INITIALIZER = "NLLB_cache_initializer.onnx";
    public static final String FILE_DECODER = "NLLB_decoder.onnx";
    public static final String FILE_EMBED_AND_LM_HEAD = "NLLB_embed_and_lm_head.onnx";
    public static final String FILE_ENCODER = "NLLB_encoder.onnx";

    private static final String[] MODEL_FILES = new String[]{
            FILE_CACHE_INITIALIZER,
            FILE_DECODER,
            FILE_EMBED_AND_LM_HEAD,
            FILE_ENCODER
    };

    public File getModelDir(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir != null) {
            return dir;
        }
        return context.getFilesDir();
    }

    public boolean areModelsPresent(Context context) {
        File dir = getModelDir(context);
        for (String name : MODEL_FILES) {
            File f = new File(dir, name);
            if (!f.exists() || f.length() <= 0) {
                return false;
            }
        }
        return true;
    }

    public void downloadAllAsync(Context context, Listener listener) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        Context appContext = context.getApplicationContext();

        new Thread(() -> {
            try {
                File dir = getModelDir(appContext);
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("Failed to create model dir: " + dir.getAbsolutePath());
                }

                long totalBytes = 0;
                for (String name : MODEL_FILES) {
                    totalBytes += getContentLength(BASE_URL + name);
                }
                if (totalBytes <= 0) {
                    totalBytes = -1;
                }

                final long finalTotalBytes = totalBytes;
                long downloadedAll = 0;
                for (String name : MODEL_FILES) {
                    File out = new File(dir, name);
                    if (out.exists() && out.length() > 0) {
                        downloadedAll += out.length();
                        continue;
                    }

                    final long downloadedBeforeThisFile = downloadedAll;
                    long downloadedThisFile = downloadSingle(BASE_URL + name, out, bytesRead -> {
                        if (finalTotalBytes > 0) {
                            long done = downloadedBeforeThisFile + bytesRead;
                            int percent = (int) Math.min(100, (done * 100L) / finalTotalBytes);
                            mainHandler.post(() -> listener.onProgress(percent, name));
                        } else {
                            mainHandler.post(() -> listener.onProgress(0, name));
                        }
                    });

                    downloadedAll += downloadedThisFile;
                    if (finalTotalBytes > 0) {
                        int percent = (int) Math.min(100, (downloadedAll * 100L) / finalTotalBytes);
                        mainHandler.post(() -> listener.onProgress(percent, name));
                    }
                }

                mainHandler.post(() -> listener.onProgress(100, MODEL_FILES[MODEL_FILES.length - 1]));
                mainHandler.post(() -> listener.onCompleted(dir));
            } catch (Exception e) {
                mainHandler.post(() -> listener.onFailure(e));
            }
        }, "nllbModelDownload").start();
    }

    private interface ProgressCallback {
        void onBytesRead(long downloadedBytes);
    }

    private static long downloadSingle(String url, File out, ProgressCallback cb) throws Exception {
        File tmp = new File(out.getAbsolutePath() + ".download");
        if (tmp.exists() && !tmp.delete()) {
            throw new IllegalStateException("Failed to delete tmp file: " + tmp.getAbsolutePath());
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(30_000);
        conn.connect();

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " for " + url);
        }

        long downloaded = 0;
        try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[1024 * 256];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                fos.write(buffer, 0, n);
                downloaded += n;
                cb.onBytesRead(downloaded);
            }
            fos.getFD().sync();
        } finally {
            conn.disconnect();
        }

        if (!tmp.renameTo(out)) {
            throw new IllegalStateException("Failed to rename " + tmp.getAbsolutePath() + " to " + out.getAbsolutePath());
        }

        return downloaded;
    }

    private static long getContentLength(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.connect();
            long len = conn.getContentLengthLong();
            return Math.max(len, 0);
        } catch (Exception ignored) {
            return 0;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
