package com.rp.rptranscription.utils;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.k2fsa.sherpa.onnx.VadKt.getVadModelConfig;

/**
 * VAD（语音活动检测）管理器
 * 封装VAD功能，支持本地ONNX语音识别和云端语音识别调用
 */
public class VadManager {
    private static final String TAG = "VadManager";
    private static volatile VadManager instance;
    
    private Context context;
    private volatile Vad vad;
    private volatile ExecutorService executorService;
    private List<VadCallback> vadCallbacks = new CopyOnWriteArrayList<>();
    
    // VAD状态标志
    private volatile boolean isInitialized = false;
    
    // 音频参数
    private final int sampleRateInHz = 16000;
    
    /**
     * VAD回调接口
     */
    public interface VadCallback {
        /**
         * 当检测到语音段时调用
         * @param segment 语音段数据
         */
        void onVoiceSegmentDetected(SpeechSegment segment);
        
        /**
         * 当VAD发生错误时调用
         * @param errorMsg 错误信息
         */
        void onVadError(String errorMsg);
    }
    
    // 私有构造函数
    private VadManager(Context context) {
        this.context = context.getApplicationContext();
        this.executorService = Executors.newSingleThreadExecutor();
    }
    
    private ExecutorService getOrCreateExecutor() {
        ExecutorService exec = executorService;
        if (exec != null && !exec.isShutdown() && !exec.isTerminated()) {
            return exec;
        }
        synchronized (this) {
            exec = executorService;
            if (exec == null || exec.isShutdown() || exec.isTerminated()) {
                executorService = Executors.newSingleThreadExecutor();
            }
            return executorService;
        }
    }
    
    /**
     * 获取VAD管理器单例实例
     * @param context 上下文
     * @return VadManager实例
     */
    public static VadManager getInstance(Context context) {
        if (instance == null) {
            synchronized (VadManager.class) {
                if (instance == null) {
                    instance = new VadManager(context);
                }
            }
        }
        return instance;
    }
    
    /**
     * 设置VAD回调（兼容旧接口，会替换所有现有回调）
     * @param callback VAD回调接口
     */
    public void setVadCallback(VadCallback callback) {
        vadCallbacks.clear();
        if (callback != null) {
            vadCallbacks.add(callback);
        }
    }
    
    /**
     * 添加VAD回调
     * @param callback VAD回调接口
     */
    public void addVadCallback(VadCallback callback) {
        if (callback != null) {
            vadCallbacks.add(callback);
        }
    }
    
    /**
     * 移除VAD回调
     * @param callback VAD回调接口
     */
    public void removeVadCallback(VadCallback callback) {
        if (callback != null) {
            vadCallbacks.remove(callback);
        }
    }
    
    /**
     * 清除所有VAD回调
     */
    public void clearVadCallbacks() {
        vadCallbacks.clear();
    }
    
    /**
     * 初始化VAD模型
     * @return 是否初始化成功
     */
    public boolean initVad() {
        if (isInitialized && vad != null) {
            return true;
        }
        
        try {
            int vadModelType = 0;
            Log.i(TAG, "Select VAD model type " + vadModelType);
            VadModelConfig config = getVadModelConfig(vadModelType);
            
            if (config == null) {
                Log.e(TAG, "VAD model config is null");
                notifyError("VAD model config is null");
                return false;
            }
            
            vad = new Vad(context.getAssets(), config);
            isInitialized = true;
            Log.i(TAG, "VAD model initialized successfully");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize VAD model: " + e.getMessage(), e);
            notifyError("Failed to initialize VAD model: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 处理音频样本
     * @param pcmSamples PCM音频样本（short[]格式）
     * @param length 有效样本长度
     */
    public void processAudioSamples(short[] pcmSamples, int length) {
        // 增强输入验证
        if (!isInitialized || vad == null) {
            return;
        }
        
        if (pcmSamples == null) {
            return;
        }
        
        // 验证length参数的有效性
        if (length <= 0) {
            return;
        }
        
        if (length > pcmSamples.length) {
            length = pcmSamples.length;
        }
        
        // 转换为float数组
        float[] floatSamples = new float[length];
        for (int i = 0; i < length; i++) {
            floatSamples[i] = pcmSamples[i] / 32768.0f;
        }
        
        processFloatSamples(floatSamples);
    }
    
    /**
     * 处理float格式的音频样本
     * @param samples float格式的音频样本
     */
    public void processFloatSamples(float[] samples) {
        // 增强输入验证
        if (!isInitialized || vad == null) {
            return;
        }
        
        if (samples == null || samples.length == 0) {
            return;
        }
        
        // 避免处理过大的样本数组，防止内存问题
        float[] processingSamples;
        if (samples.length > 10240) { // 限制最大500ms的16kHz音频
            processingSamples = new float[10240];
            System.arraycopy(samples, 0, processingSamples, 0, 10240);
        } else {
            processingSamples = samples;
        }
        
        // 创建样本的副本，避免并发修改问题
        float[] samplesCopy = processingSamples.clone();
        
        ExecutorService exec = getOrCreateExecutor();
        if (exec == null || exec.isShutdown() || exec.isTerminated()) {
            return;
        }
        
        try {
            exec.execute(() -> {
                // 再次检查VAD状态，确保在执行时VAD仍然有效
                if (!isInitialized || vad == null) {
                    return;
                }
                
                try {
                    // 处理VAD
                    vad.acceptWaveform(samplesCopy);
                    
                    // 处理检测到的语音段
                    while (vad != null && !vad.empty()) {
                        SpeechSegment segment = vad.front();
                        if (segment != null && segment.getSamples() != null && segment.getSamples().length > 0) {
                            // 通知所有注册的回调
                            for (VadCallback callback : vadCallbacks) {
                                try {
                                    callback.onVoiceSegmentDetected(segment);
                                } catch (Exception e) {
                                    Log.e(TAG, "Error in VAD callback: " + e.getMessage(), e);
                                }
                            }
                        }
                        
                        // 弹出已处理的语音段
                        vad.pop();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing VAD: " + e.getMessage(), e);
                    notifyError("Error processing VAD: " + e.getMessage());
                    
                    // 尝试重置VAD
                    try {
                        reset();
                    } catch (Exception resetEx) {
                        Log.e(TAG, "Failed to reset VAD: " + resetEx.getMessage(), resetEx);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }
    
    public void flush() {
        if (!isInitialized || vad == null) {
            return;
        }
        
        ExecutorService exec = getOrCreateExecutor();
        if (exec == null || exec.isShutdown() || exec.isTerminated()) {
            return;
        }
        
        try {
            exec.execute(() -> {
                if (!isInitialized || vad == null) {
                    return;
                }
                
                try {
                    vad.flush();
                    
                    while (vad != null && !vad.empty()) {
                        SpeechSegment segment = vad.front();
                        if (segment != null && segment.getSamples() != null && segment.getSamples().length > 0) {
                            for (VadCallback callback : vadCallbacks) {
                                try {
                                    callback.onVoiceSegmentDetected(segment);
                                } catch (Exception e) {
                                    Log.e(TAG, "Error in VAD callback: " + e.getMessage(), e);
                                }
                            }
                        }
                        vad.pop();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error flushing VAD: " + e.getMessage(), e);
                    notifyError("Error flushing VAD: " + e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }
    
    /**
     * 重置VAD状态
     */
    public void reset() {
        if (!isInitialized || vad == null) {
            return;
        }
        
        try {
            vad.reset();
        } catch (Exception e) {
            Log.e(TAG, "Failed to reset VAD: " + e.getMessage(), e);
            notifyError("Failed to reset VAD: " + e.getMessage());
            
            // 如果重置失败，尝试重新初始化VAD
            try {
                initVad();
            } catch (Exception initEx) {
                Log.e(TAG, "Failed to reinitialize VAD: " + initEx.getMessage(), initEx);
            }
        }
    }
    
    /**
     * 释放VAD资源
     */
    public void release() {
        // 标记为未初始化，防止新的处理请求
        isInitialized = false;
        
        if (vad != null) {
            try {
                vad.release();
            } catch (Exception e) {
                Log.e(TAG, "Failed to release VAD: " + e.getMessage(), e);
            } finally {
                vad = null;
            }
        }
        
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        executorService = null;
    }
    
    /**
     * 检查VAD是否已初始化
     * @return 是否已初始化
     */
    public boolean isInitialized() {
        return isInitialized;
    }
    
    /**
     * 通知VAD错误
     * @param errorMsg 错误信息
     */
    private void notifyError(String errorMsg) {
        // 通知所有注册的回调
        for (VadCallback callback : vadCallbacks) {
            try {
                callback.onVadError(errorMsg);
            } catch (Exception e) {
                Log.e(TAG, "Error in VAD error callback: " + e.getMessage(), e);
            }
        }
    }
}