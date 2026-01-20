package com.rp.rptranscription.utils;


import static com.k2fsa.sherpa.onnx.FeatureConfigKt.getFeatureConfig;
import static com.k2fsa.sherpa.onnx.OfflineRecognizerKt.getOfflineModelConfig;
import static com.k2fsa.sherpa.onnx.VadKt.getVadModelConfig;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.SpeechSegment;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 语音识别管理器 - 单例模式
 * 使用ONNX模型进行实时语音识别+VAD
 */
public class VoiceRecognitionAndVadManager {
    private static final String TAG = "VoiceRecognitionAndVadManager";
    private static volatile VoiceRecognitionAndVadManager instance;
    private Context context;

    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private final String[] permissions = {Manifest.permission.RECORD_AUDIO};

    // 音频录制参数
    private final int audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
    private final int sampleRateInHz = 16000;
    private final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

    // VAD和识别器
    private VadManager vadManager;
    private OfflineRecognizer offlineRecognizer;
    private AudioRecord audioRecord;
    private Thread recordingThread;

    // 状态标志
    private final AtomicBoolean isRecording = new AtomicBoolean(false);

    private static final int PARTIAL_REFRESH_INTERVAL_MS = 500;
    private static final int PARTIAL_WINDOW_MS = 2500;
    private static final int PARTIAL_MIN_WINDOW_MS = 700;
    private static final int PARTIAL_MAX_TEXT_LEN = 200;

    private final Object ringLock = new Object();
    private final int ringCapacitySamples = (int) (sampleRateInHz * (PARTIAL_WINDOW_MS / 1000.0f));
    private float[] ringBuffer = new float[ringCapacitySamples];
    private int ringWritePos = 0;
    private int ringSizeFilled = 0;

    private volatile String stickyPartialText = "";
    private final AtomicBoolean partialPending = new AtomicBoolean(false);
    private ScheduledExecutorService partialScheduler;
    private ScheduledFuture<?> partialFuture;

    // 回调接口
    private RecognitionCallback recognitionCallback;

    // 线程池
    private ExecutorService executorService;
    private Handler mainHandler;

    public boolean isSpeechOutputActive() {
        return isSpeechOutputActive;
    }

    // 语音输出活动状态标志
    private volatile boolean isSpeechOutputActive = false;

    // 当前识别语言
    private RecognitionLanguage mainLanguage = RecognitionLanguage.CHINESE;

    // 私有构造函数
    private VoiceRecognitionAndVadManager(Context context) {
        this.context = context.getApplicationContext();
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.vadManager = VadManager.getInstance(context);
    }

    // 获取单例实例
    public static VoiceRecognitionAndVadManager getInstance(Context context) {
        if (instance == null) {
            synchronized (VoiceRecognitionAndVadManager.class) {
                if (instance == null) {
                    instance = new VoiceRecognitionAndVadManager(context);
                }
            }
        }
        return instance;
    }

    // 识别结果回调接口
    public interface RecognitionCallback {
        void onRecognitionResult(String result);

        default void onRecognitionPartial(String partial) {
        }

        void onRecognitionError(String errorMsg);
    }

    // 设置回调
    public void setRecognitionCallback(RecognitionCallback callback) {
        this.recognitionCallback = callback;
    }

    // 初始化VAD模型
    private boolean initVadModel() {
        try {
            if (vadManager.initVad()) {
                vadManager.setVadCallback(new VadManager.VadCallback() {
                    @Override
                    public void onVoiceSegmentDetected(SpeechSegment segment) {
                        // 处理检测到的语音段
                        processVoiceSegment(segment);
                    }
                    
                    @Override
                    public void onVadError(String errorMsg) {
                        Log.e(TAG, "VAD error: " + errorMsg);
                        notifyError("VAD error: " + errorMsg);
                    }
                });
                Log.i(TAG, "VAD model initialized successfully through VadManager");
                return true;
            } else {
                Log.e(TAG, "Failed to initialize VAD model through VadManager");
                notifyError("Failed to initialize VAD model through VadManager");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize VAD model: " + e.getMessage(), e);
            notifyError("Failed to initialize VAD model: " + e.getMessage());
            return false;
        }
    }
    
    // 处理VAD检测到的语音段
    private void processVoiceSegment(SpeechSegment segment) {
        if (segment == null) return;
        
        float[] segmentSamples = segment.getSamples();
        // 确保语音段样本有效
        if (segmentSamples != null && segmentSamples.length > 0) {
            // 在后台线程处理语音识别
            executorService.execute(() -> {
                try {
                    String text = runSecondPass(segmentSamples);
                    if (!text.isEmpty()) {
                        updateRecognitionResult(text);
                        updateRecognitionPartial("");
                        resetPartialState();
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error in recognition thread: " + ex.getMessage(), ex);
                }
            });
        }
    }

    // 初始化离线识别器
    private boolean initOfflineRecognizer() {
        try {
            // 使用与MainActivity.kt相同的模型类型
            int asrModelType = 15;//15亚洲5种语言（中日韩粤英）     40欧洲25个国家语言，需要下载对应的模型
            Log.i(TAG, "Select model type " + asrModelType + " for ASR");

            FeatureConfig featConfig = getFeatureConfig(sampleRateInHz, 80);
            OfflineModelConfig modelConfig = getOfflineModelConfig(asrModelType);

            if (modelConfig == null) {
                Log.e(TAG, "ASR model config is null");
                return false;
            }

            // OfflineRecognizerConfig是Kotlin数据类，直接使用构造函数创建实例
            OfflineRecognizerConfig config = new OfflineRecognizerConfig(
                    featConfig,
                    modelConfig,
                    new HomophoneReplacerConfig("", "", ""), // hr参数：dictDir, lexicon, ruleFsts
                    "greedy_search", // decodingMethod
                    4, // maxActivePaths
                    "", // hotwordsFile
                    1.5f, // hotwordsScore
                    "", // ruleFsts
                    "", // ruleFars
                    0.0f // blankPenalty
            );

            offlineRecognizer = new OfflineRecognizer(
                    context.getAssets(),
                    config
            );
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize offline recognizer: " + e.getMessage(), e);
            notifyError("Failed to initialize offline recognizer: " + e.getMessage());
            return false;
        }
    }

    // 初始化麦克风
    private boolean initMicrophone() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Audio record permission not granted");
            notifyError("Audio record permission not granted");
            return false;
        }

        int numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        Log.i(TAG, "buffer size in milliseconds: " + (numBytes * 1000.0f / sampleRateInHz));

        audioRecord = new AudioRecord(
                audioSource,
                sampleRateInHz,
                channelConfig,
                audioFormat,
                numBytes * 2
        );

        return audioRecord.getState() == AudioRecord.STATE_INITIALIZED;
    }

    // 开始录音（与VoiceRecognitionManager兼容的方法名）
    public boolean startRecognition() {
        return startRecording();
    }

    // 停止录音（与VoiceRecognitionManager兼容的方法名）
    public void stopRecognition() {
        stopRecording();
    }

    // 开始录音
    public boolean startRecording() {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording");
            return false;
        }

        // 初始化模型
        if (vadManager == null || !vadManager.isInitialized()) {
            if (!initVadModel()) {
                return false;
            }
        } else {
            // 确保重新设置VAD回调，避免被其他组件（如云端识别）清除
            initVadModel();
        }

        if (offlineRecognizer == null && !initOfflineRecognizer()) {
            return false;
        }

        // 初始化麦克风
        if (!initMicrophone()) {
            Log.e(TAG, "Failed to initialize microphone");
            return false;
        }

        // 重置状态
        try {
            vadManager.reset();
        } catch (Exception e) {
            Log.e(TAG, "Error resetting VAD state: " + e.getMessage(), e);
            // 如果重置失败，尝试重新初始化VAD
            if (!initVadModel()) {
                Log.e(TAG, "Failed to reinitialize VAD model");
                return false;
            }
        }

        // 开始录音
        audioRecord.startRecording();
        isRecording.set(true);

        resetPartialState();
        startPartialLoop();

        // 启动处理线程
        recordingThread = new Thread(this::processSamples, "RecordingThread");
        recordingThread.start();

        Log.i(TAG, "Started recording");
        return true;
    }

    // 停止录音 - 默认不释放麦克风资源
    public void stopRecording() {
        stopRecording(false); // 默认不释放麦克风资源
    }

    // 停止录音 - 重载版本：支持控制是否释放麦克风资源
    public void stopRecording(boolean releaseMicrophone) {
        if (!isRecording.get()) {
            return;
        }

        // 首先设置录音状态为false，让线程自然退出
        isRecording.set(false);

        // 尽早停止录音，避免 AudioRecord.read() 长时间阻塞导致 join 超时
        synchronized (this) {
            if (audioRecord != null) {
                try {
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping audio record early: " + e.getMessage(), e);
                }
            }
        }

        stopPartialLoop();
        updateRecognitionPartial("");

        if (vadManager != null && vadManager.isInitialized()) {
            try {
                vadManager.flush();
            } catch (Exception e) {
                Log.e(TAG, "Error flushing VAD when stopping recording: " + e.getMessage(), e);
            }
        }

        // 等待线程结束
        if (recordingThread != null) {
            try {
                // 给线程一个合理的时间来结束
                recordingThread.join(1000);
                if (recordingThread.isAlive()) {
                    Log.w(TAG, "Recording thread did not exit in time");
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while waiting for recording thread: " + e.getMessage(), e);
            } finally {
                recordingThread = null;
            }
        }

        // 线程结束后再停止/释放资源
        synchronized (this) {
            if (audioRecord != null) {
                try {
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();
                    }
                    if (releaseMicrophone) {
                        audioRecord.release();
                        audioRecord = null;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping audio record: " + e.getMessage(), e);
                    if (releaseMicrophone) {
                        audioRecord = null;
                    }
                }
            }
        }

        // 重置VAD管理器状态
        if (vadManager != null && vadManager.isInitialized()) {
            try {
                vadManager.reset();
            } catch (Exception e) {
                Log.e(TAG, "Error resetting VAD manager when stopping recording: " + e.getMessage(), e);
            }
        }
    }

    // 处理音频样本
    private void processSamples() {
        final int bufferSize = 512;
        short[] buffer = new short[bufferSize];

        while (isRecording.get()) {
            try {
                AudioRecord localAudioRecord = null;
                synchronized (this) {
                    // 在同步块内获取audioRecord引用，避免资源竞争
                    localAudioRecord = audioRecord;
                }
                
                // 检查audioRecord是否仍然有效
                if (localAudioRecord == null) {
                    Log.e(TAG, "audioRecord is null, exiting processing loop");
                    break;
                }
                
                // 检查AudioRecord的状态，确保它处于正确状态
                int state = localAudioRecord.getState();
                if (state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord is not initialized, state: " + state);
                    break;
                }
                
                // 检查AudioRecord的录制状态，确保它处于录制状态
                int recordingState = localAudioRecord.getRecordingState();
                if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Thread.sleep(100); // 短暂延迟后重试
                    continue;
                }
                
                // 使用try-catch包装read调用，防止底层异常导致崩溃
                int ret = -1;
                try {
                    ret = localAudioRecord.read(buffer, 0, buffer.length);
                } catch (Exception e) {
                    Log.e(TAG, "Exception in AudioRecord.read(): " + e.getMessage(), e);
                    // 发生异常时，检查录音状态
                    if (localAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioRecord state invalid after read exception, exiting");
                        break;
                    }
                    continue;
                } catch (Error error) {
                    Log.e(TAG, "Error in AudioRecord.read(): " + error.getMessage(), error);
                    // 捕获Error以防止崩溃
                    break;
                }
                
                // 检查read返回值
                if (ret == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "AudioRecord read failed: ERROR_INVALID_OPERATION");
                    break; // 无效操作，退出循环
                } else if (ret == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord read failed: ERROR_BAD_VALUE");
                    break; // 参数错误，退出循环
                } else if (ret < 0) {
                    continue; // 其他错误，继续循环
                } else if (ret == 0) {
                    // 没有读取到数据，继续循环
                    continue;
                }
                
                if (ret > 0) {
                    if (isSpeechOutputActive) {
                        // 检查是否有语音输出正在进行，如果有则只读取数据但不处理（清空缓冲区）
                        continue;
                    }

                    if (!isRecording.get()) {
                        break;
                    }
                    
                    float[] samples = new float[ret];
                    for (int i = 0; i < ret; i++) {
                        samples[i] = buffer[i] / 32768.0f;
                    }

                    appendToRing(samples);

                    // 检查VAD管理器是否有效且样本数组有效
                    if (vadManager != null && vadManager.isInitialized() && samples != null && samples.length > 0) {
                        // 使用VAD管理器处理音频样本
                        try {
                            vadManager.processFloatSamples(samples);
                        } catch (java.util.concurrent.RejectedExecutionException ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in recording thread: " + e.getMessage(), e);
                // 为了防止崩溃，这里简单地继续循环
                // 避免在异常处理中进行复杂操作
                try {
                    // 短暂延迟，避免在异常后立即重试导致CPU占用过高
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // 运行第二遍识别
    private String runSecondPass(float[] samples) {
        try {
            final long t0 = SystemClock.elapsedRealtime();

            String result = runAsr(samples);

            final long t1 = SystemClock.elapsedRealtime();
            Log.i(TAG, "Offline ASR耗时: " + (t1 - t0) + "ms, text=\"" + result + "\"");
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error in runSecondPass: " + e.getMessage(), e);
            return "";
        }
    }

    private String runAsr(float[] samples) {
        OfflineStream stream = offlineRecognizer.createStream();
        stream.acceptWaveform(samples, sampleRateInHz);
        offlineRecognizer.decode(stream);
        String result = offlineRecognizer.getResult(stream).getText();
        stream.release();
        return result == null ? "" : result;
    }

    // 更新识别结果
    private void updateRecognitionResult(String text) {
        mainHandler.post(() -> {
            if (recognitionCallback != null) {
                Log.i(TAG, "Recognition result callback: text=\"" + text + "\"");
                recognitionCallback.onRecognitionResult(text);
            }
        });
    }

    private void updateRecognitionPartial(String partial) {
        mainHandler.post(() -> {
            if (recognitionCallback != null) {
                recognitionCallback.onRecognitionPartial(partial);
            }
        });
    }

    private void resetPartialState() {
        synchronized (ringLock) {
            ringWritePos = 0;
            ringSizeFilled = 0;
        }
        stickyPartialText = "";
        partialPending.set(false);
    }

    private String mergePartial(String base, String update) {
        if (base == null) {
            base = "";
        }
        if (update == null) {
            update = "";
        }

        base = base.trim();
        update = update.trim();

        if (update.isEmpty()) {
            return base;
        }

        if (base.isEmpty()) {
            return update;
        }

        if (update.equals(base)) {
            return base;
        }

        if (update.contains(base)) {
            return update;
        }

        if (base.contains(update)) {
            return base;
        }

        int maxOverlap = Math.min(Math.min(base.length(), update.length()), 16);
        for (int k = maxOverlap; k >= 1; k--) {
            if (base.regionMatches(base.length() - k, update, 0, k)) {
                return base + update.substring(k);
            }
        }

        return base + update;
    }

    private void appendToRing(float[] samples) {
        if (samples == null || samples.length == 0) {
            return;
        }

        synchronized (ringLock) {
            for (float v : samples) {
                ringBuffer[ringWritePos] = v;
                ringWritePos++;
                if (ringWritePos >= ringBuffer.length) {
                    ringWritePos = 0;
                }
                if (ringSizeFilled < ringBuffer.length) {
                    ringSizeFilled++;
                }
            }
        }
    }

    private float[] snapshotRing() {
        synchronized (ringLock) {
            int n = ringSizeFilled;
            if (n <= 0) {
                return new float[0];
            }

            float[] out = new float[n];
            int start = ringWritePos - n;
            if (start < 0) {
                start += ringBuffer.length;
            }

            for (int i = 0; i < n; i++) {
                int idx = start + i;
                if (idx >= ringBuffer.length) {
                    idx -= ringBuffer.length;
                }
                out[i] = ringBuffer[idx];
            }
            return out;
        }
    }

    private void startPartialLoop() {
        if (partialScheduler == null) {
            partialScheduler = Executors.newSingleThreadScheduledExecutor();
        }

        if (partialFuture != null) {
            return;
        }

        partialFuture = partialScheduler.scheduleAtFixedRate(() -> {
            if (!isRecording.get()) {
                return;
            }
            if (isSpeechOutputActive) {
                return;
            }
            if (offlineRecognizer == null) {
                return;
            }
            if (partialPending.getAndSet(true)) {
                return;
            }

            executorService.execute(() -> {
                try {
                    if (!isRecording.get() || isSpeechOutputActive) {
                        return;
                    }

                    float[] window = snapshotRing();
                    int minSamples = (int) (sampleRateInHz * (PARTIAL_MIN_WINDOW_MS / 1000.0f));
                    if (window.length < minSamples) {
                        return;
                    }

                    String text = runAsr(window);
                    if (text == null) {
                        text = "";
                    }
                    text = text.trim();
                    if (text.length() > PARTIAL_MAX_TEXT_LEN) {
                        text = text.substring(text.length() - PARTIAL_MAX_TEXT_LEN);
                    }

                    String merged = mergePartial(stickyPartialText, text);
                    if (merged.length() > PARTIAL_MAX_TEXT_LEN) {
                        merged = merged.substring(merged.length() - PARTIAL_MAX_TEXT_LEN);
                    }

                    if (!merged.equals(stickyPartialText)) {
                        stickyPartialText = merged;
                        updateRecognitionPartial(stickyPartialText);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error generating partial ASR: " + e.getMessage(), e);
                } finally {
                    partialPending.set(false);
                }
            });
        }, PARTIAL_REFRESH_INTERVAL_MS, PARTIAL_REFRESH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPartialLoop() {
        if (partialFuture != null) {
            try {
                partialFuture.cancel(true);
            } catch (Exception ignored) {
            } finally {
                partialFuture = null;
            }
        }

        if (partialScheduler != null) {
            try {
                partialScheduler.shutdownNow();
            } catch (Exception ignored) {
            } finally {
                partialScheduler = null;
            }
        }
    }

    // 通知错误
    private void notifyError(String errorMsg) {
        mainHandler.post(() -> {
            if (recognitionCallback != null) {
                recognitionCallback.onRecognitionError(errorMsg);
            }
        });
    }

    // 检查权限
    public boolean checkPermissions() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    // 释放资源
    public void release() {
        // 确保完全停止录音并释放麦克风资源
        stopRecording(true);

        stopPartialLoop();

        if (offlineRecognizer != null) {
            try {
                offlineRecognizer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing recognizer: " + e.getMessage(), e);
            } finally {
                offlineRecognizer = null;
            }
        }

        if (vadManager != null) {
            try {
                vadManager.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing VAD: " + e.getMessage(), e);
            }
        }

        if (executorService != null) {
            executorService.shutdown();
            executorService = null;
        }

        instance = null;
    }

    // 获取当前识别语言
    public RecognitionLanguage getMainLanguage() {
        return mainLanguage;
    }

    // 设置当前识别语言
    public void setMainLanguage(RecognitionLanguage mainLanguage) {
        if (mainLanguage != null) {
            this.mainLanguage = mainLanguage;
        }
    }

    // 设置当前识别语言（通过语言代码）
    public void setMainLanguage(String languageCode) {
        this.mainLanguage = RecognitionLanguage.fromCode(languageCode);
    }

    // 设置语音输出活动状态
    public void setSpeechOutputActive(boolean isActive) {
        this.isSpeechOutputActive = isActive;
    }

    // 根据系统语言自动设置识别语言
    public void setLanguageBasedOnSystemLanguage() {
        String systemLanguage = System.getProperty("user.language");
        RecognitionLanguage recognitionLanguage = RecognitionLanguage.CHINESE; // 默认中文

        if (systemLanguage.equals("en")) {
            recognitionLanguage = RecognitionLanguage.ENGLISH;
        } else if (systemLanguage.equals("ko")) {
            recognitionLanguage = RecognitionLanguage.KOREAN;
        } else if (systemLanguage.equals("ja")) {
            recognitionLanguage = RecognitionLanguage.JAPANESE;
        } else if (systemLanguage.equals("yue")) {
            recognitionLanguage = RecognitionLanguage.CANTONESE;
        } else if (systemLanguage.equals("zh")) {
            recognitionLanguage = RecognitionLanguage.CHINESE;
        }

        // 只有当语言发生变化时才设置，避免不必要的重新初始化
        if (this.mainLanguage != recognitionLanguage) {
            this.mainLanguage = recognitionLanguage;
            Log.d(TAG, "系统语言变更，更新识别语言为: " + recognitionLanguage.getName() + " (" + recognitionLanguage.getCode() + ")");
            
            // 语言变更时重新初始化识别器，确保使用正确的语言增强设置
            if (isRecording.get()) {
                stopRecording();
            }
            initOfflineRecognizer();
            if (isRecording.get()) {
                startRecording();
            }
        }
    }

    // 重置识别状态
    public void resetRecognition() {
        // 重置语音输出活动状态
        isSpeechOutputActive = false;
        
        // 如果正在录音，重置VAD状态
        if (isRecording.get() && vadManager != null) {
            try {
                vadManager.reset();
            } catch (Exception e) {
                Log.e(TAG, "重置VAD状态失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 检查识别管理器是否正在工作中
     * @return 是否正在识别
     */
    public boolean isCurrentlyRecognizing() {
        return recordingThread != null && recordingThread.isAlive() && isRecording.get();
    }

    // 预加载语音模型（兼容VoiceRecognitionManager的接口）
    public boolean preloadSenseVoiceModel() {
        Log.d(TAG, "预加载语音模型...");
        // VoiceRecognitionAndVadManager会在startRecording时自动初始化模型
        // 这里我们可以简单返回true，表示模型已准备好
        return true;
    }

    // 语音识别支持的语言枚举
    public enum RecognitionLanguage {
        CHINESE("zh", "中文"),
        ENGLISH("en", "英文"),
        JAPANESE("ja", "日文"),
        KOREAN("ko", "韩文"),
        CANTONESE("yue", "粤语");

        private final String code;
        private final String name;

        RecognitionLanguage(String code, String name) {
            this.code = code;
            this.name = name;
        }

        public String getCode() {
            return code;
        }

        public String getName() {
            return name;
        }

        // 根据语言代码获取对应的枚举值
        public static RecognitionLanguage fromCode(String code) {
            if (code == null || code.isEmpty()) {
                return CHINESE;
            }
            for (RecognitionLanguage lang : values()) {
                if (lang.code.equalsIgnoreCase(code)) {
                    return lang;
                }
            }
            
            // 如果没有完全匹配，尝试根据语言代码前缀匹配
            String lowerCode = code.toLowerCase();
            if (lowerCode.startsWith("en")) {
                return ENGLISH;
            } else if (lowerCode.startsWith("ja")) {
                return JAPANESE;
            } else if (lowerCode.startsWith("ko")) {
                return KOREAN;
            } else if (lowerCode.startsWith("yue") || lowerCode.startsWith("zh-tw") || lowerCode.startsWith("zh-hk")) {
                return CANTONESE;
            } else if (lowerCode.startsWith("zh")) {
                return CHINESE;
            }
            
            // 如果仍然没有匹配，返回英文作为默认值
            return ENGLISH;
        }
    }

}