package com.rp.rptranscription;

import android.Manifest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.rp.rptranscription.utils.VoiceRecognitionAndVadManager;
import com.rp.rptranscription.ui.LanguageSelectionDialog;
import com.rp.rptranscription.translation.NllbModelDownloader;
import com.rp.rptranscription.translation.TranslationManager;
import com.rp.rptranscription.tools.CustomLocale;
import com.rp.rptranscription.voice_translation.neural_networks.NeuralNetworkApi;
import com.rp.rptranscription.voice_translation.neural_networks.translation.Translator;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;

    private Button recordButton;
    private Button downloadModelsButton;
    private ProgressBar downloadProgress;
    private TextView textView;
    private TextView targetLanguageValue;
    private TextView translatedTextView;
    private VoiceRecognitionAndVadManager manager;

    private boolean isRunning = false;
    private volatile boolean isBusy = false;
    private final StringBuilder resultBuffer = new StringBuilder();
    private String partialLine = "";

    private long asrLineCount = 0;
    private final StringBuilder translatedBuffer = new StringBuilder();
    private String translatedPartialLine = "";
    private long translatedLineCount = 0;
    private final Map<Long, String> pendingFinalTranslations = new HashMap<>();
    private long partialTranslateSeq = 0;
    private Runnable pendingPartialTranslateRunnable;

    private String selectedTargetLanguageCode = "en";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService translationExecutor = Executors.newSingleThreadExecutor();
    private long translateToken = 0;

    private NllbModelDownloader modelDownloader;
    private TranslationManager translationManager;
    private volatile boolean downloadInProgress = false;
    private volatile boolean translatorInitRequested = false;
    private volatile boolean translatorReady = false;

    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        recordButton = findViewById(R.id.record_button);
        downloadModelsButton = findViewById(R.id.download_models_button);
        downloadProgress = findViewById(R.id.download_progress);
        textView = findViewById(R.id.my_text);
        textView.setMovementMethod(new ScrollingMovementMethod());

        targetLanguageValue = findViewById(R.id.target_language_value);
        translatedTextView = findViewById(R.id.translated_text);
        translatedTextView.setMovementMethod(new ScrollingMovementMethod());

        modelDownloader = new NllbModelDownloader();
        translationManager = new TranslationManager(this);

        refreshModelUi();
        downloadModelsButton.setOnClickListener(v -> startModelDownloadIfNeeded());

        updateTargetLanguageValue();
        updateTranslatedSubtitlePlaceholder(selectedTargetLanguageCode);
        targetLanguageValue.setOnClickListener(v -> showTargetLanguageDialog());

        manager = VoiceRecognitionAndVadManager.getInstance(this);
        manager.setRecognitionCallback(new VoiceRecognitionAndVadManager.RecognitionCallback() {
            @Override
            public void onRecognitionResult(String result) {
                if (result == null || result.trim().isEmpty()) {
                    return;
                }
                partialLine = "";
                translatedPartialLine = "";
                if (resultBuffer.length() > 0) {
                    resultBuffer.append('\n');
                }
                resultBuffer.append(result);
                asrLineCount++;
                textView.setText(resultBuffer.toString());

                requestTranslate(result, true, asrLineCount);
            }

            @Override
            public void onRecognitionPartial(String partial) {
                if (partial == null) {
                    partial = "";
                }
                partial = partial.trim();
                partialLine = partial;

                if (partialLine.isEmpty()) {
                    textView.setText(resultBuffer.toString());
                    translatedPartialLine = "";
                    updateTranslatedTextView();
                    return;
                }

                if (resultBuffer.length() == 0) {
                    textView.setText(partialLine);
                } else {
                    textView.setText(resultBuffer.toString() + "\n" + partialLine);
                }

                requestTranslate(partialLine, false, asrLineCount + 1);
            }

            @Override
            public void onRecognitionError(String errorMsg) {
                Toast.makeText(MainActivity.this, errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        recordButton.setOnClickListener(v -> toggleRecognition());

        if (!manager.checkPermissions()) {
            recordButton.setEnabled(false);
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO_PERMISSION
            );
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void showTargetLanguageDialog() {
        LanguageSelectionDialog dialog = new LanguageSelectionDialog(
                this,
                R.raw.nllb_supported_languages,
                selectedTargetLanguageCode,
                code -> {
                    selectedTargetLanguageCode = code;
                    updateTargetLanguageValue();
                    updateTranslatedSubtitlePlaceholder(selectedTargetLanguageCode);
                }
        );
        dialog.show();
    }

    private void updateTargetLanguageValue() {
        String display = getDisplayNameForCode(selectedTargetLanguageCode);
        targetLanguageValue.setText(display + " (" + selectedTargetLanguageCode + ")");
    }

    private void updateTranslatedSubtitlePlaceholder(String targetLanguageCode) {
        translatedTextView.setText("翻译字幕将在此处显示");
    }

    private String getDisplayNameForCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return "";
        }
        Locale locale = Locale.forLanguageTag(code);
        String name = locale.getDisplayName(locale);
        if (name == null || name.trim().isEmpty() || "und".equalsIgnoreCase(locale.getLanguage())) {
            return code;
        }
        String trimmed = name.trim();
        return trimmed.substring(0, 1).toUpperCase(locale) + trimmed.substring(1);
    }

    private void toggleRecognition() {
        if (!manager.checkPermissions()) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO_PERMISSION
            );
            return;
        }

        if (!isRunning) {
            if (isBusy) {
                return;
            }
            resultBuffer.setLength(0);
            partialLine = "";
            asrLineCount = 0;
            textView.setText("");

            translatedBuffer.setLength(0);
            translatedPartialLine = "";
            translatedLineCount = 0;
            pendingFinalTranslations.clear();
            translatedTextView.setText("");

            isBusy = true;
            recordButton.setEnabled(false);
            controlExecutor.execute(() -> {
                boolean started = manager.startRecognition();
                runOnUiThread(() -> {
                    if (!started) {
                        Toast.makeText(this, "Failed to start recognition", Toast.LENGTH_SHORT).show();
                    } else {
                        isRunning = true;
                        recordButton.setText(R.string.stop);
                    }
                    recordButton.setEnabled(true);
                    isBusy = false;
                });
            });
        } else {
            if (isBusy) {
                return;
            }
            isBusy = true;
            recordButton.setEnabled(false);
            controlExecutor.execute(() -> {
                manager.stopRecording(true);
                runOnUiThread(() -> {
                    isRunning = false;
                    recordButton.setText(R.string.start);
                    recordButton.setEnabled(true);
                    isBusy = false;
                });
            });
        }
    }

    private void refreshModelUi() {
        boolean ready = modelDownloader.areModelsPresent(this);
        if (ready) {
            downloadModelsButton.setText(R.string.models_ready);
            downloadModelsButton.setEnabled(false);
            downloadProgress.setVisibility(android.view.View.GONE);
            ensureTranslatorInitialized();
        } else {
            downloadModelsButton.setText(R.string.download_models);
            downloadModelsButton.setEnabled(!downloadInProgress);
            if (!downloadInProgress) {
                downloadProgress.setVisibility(android.view.View.GONE);
            }
        }
    }

    private void startModelDownloadIfNeeded() {
        if (downloadInProgress) {
            return;
        }
        if (modelDownloader.areModelsPresent(this)) {
            refreshModelUi();
            return;
        }

        downloadInProgress = true;
        downloadProgress.setProgress(0);
        downloadProgress.setVisibility(android.view.View.VISIBLE);
        downloadModelsButton.setEnabled(false);
        downloadModelsButton.setText(R.string.downloading_models);

        modelDownloader.downloadAllAsync(this, new NllbModelDownloader.Listener() {
            @Override
            public void onProgress(int percent, String currentFileName) {
                downloadProgress.setProgress(percent);
            }

            @Override
            public void onCompleted(File modelDir) {
                downloadInProgress = false;
                Toast.makeText(MainActivity.this, "模型下载完成: " + modelDir.getAbsolutePath(), Toast.LENGTH_SHORT).show();
                refreshModelUi();
            }

            @Override
            public void onFailure(Exception e) {
                downloadInProgress = false;
                Toast.makeText(MainActivity.this, "模型下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                downloadProgress.setVisibility(android.view.View.GONE);
                downloadModelsButton.setEnabled(true);
                downloadModelsButton.setText(R.string.download_models);
            }
        });
    }

    private void ensureTranslatorInitialized() {
        if (translatorReady || translatorInitRequested) {
            return;
        }
        if (!modelDownloader.areModelsPresent(this)) {
            return;
        }

        translatorInitRequested = true;
        translationManager.initIfNeeded(new NeuralNetworkApi.InitListener() {
            @Override
            public void onInitializationFinished() {
                translatorReady = true;
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "离线翻译初始化完成", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(int[] reasons, long value) {
                translatorInitRequested = false;
                translatorReady = false;
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "离线翻译初始化失败", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void requestTranslate(String text, boolean isFinal) {
        requestTranslate(text, isFinal, 0);
    }

    private void requestTranslate(String text, boolean isFinal, long lineId) {
        if (text == null) {
            return;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (!modelDownloader.areModelsPresent(this)) {
            return;
        }

        ensureTranslatorInitialized();

        if (isFinal) {
            if (pendingPartialTranslateRunnable != null) {
                mainHandler.removeCallbacks(pendingPartialTranslateRunnable);
                pendingPartialTranslateRunnable = null;
            }
            translationExecutor.execute(() -> doTranslateFinal(trimmed, lineId));
        } else {
            long seq = ++partialTranslateSeq;
            if (pendingPartialTranslateRunnable != null) {
                mainHandler.removeCallbacks(pendingPartialTranslateRunnable);
            }
            pendingPartialTranslateRunnable = () -> translationExecutor.execute(() -> doTranslatePartial(trimmed, lineId, seq));
            mainHandler.postDelayed(pendingPartialTranslateRunnable, 350);
        }
    }

    private void doTranslatePartial(String text, long lineId, long seq) {
        if (seq != partialTranslateSeq) {
            return;
        }
        Translator translator = translationManager.getTranslatorOrNull();
        if (translator == null) {
            return;
        }

        CustomLocale src = new CustomLocale("zh");
        CustomLocale dst = new CustomLocale(selectedTargetLanguageCode);

        translator.translate(text, src, dst, 1, new Translator.TranslateListener() {
            @Override
            public void onTranslatedText(String textToTranslate, String translatedText, long resultID, boolean isFinal, CustomLocale languageOfText) {
                if (seq != partialTranslateSeq) {
                    return;
                }
                if (lineId != asrLineCount + 1) {
                    return;
                }
                translatedPartialLine = translatedText;
                runOnUiThread(() -> updateTranslatedTextView());
            }

            @Override
            public void onFailure(int[] reasons, long value) {
            }
        });
    }

    private void doTranslateFinal(String text, long lineId) {
        Translator translator = translationManager.getTranslatorOrNull();
        if (translator == null) {
            return;
        }

        CustomLocale src = new CustomLocale("zh");
        CustomLocale dst = new CustomLocale(selectedTargetLanguageCode);

        translator.translate(text, src, dst, 1, new Translator.TranslateListener() {
            @Override
            public void onTranslatedText(String textToTranslate, String translatedText, long resultID, boolean isFinal, CustomLocale languageOfText) {
                if (!isFinal) {
                    return;
                }
                synchronized (pendingFinalTranslations) {
                    pendingFinalTranslations.put(lineId, translatedText);
                    while (pendingFinalTranslations.containsKey(translatedLineCount + 1)) {
                        String next = pendingFinalTranslations.remove(translatedLineCount + 1);
                        if (next == null) {
                            break;
                        }
                        if (translatedBuffer.length() > 0) {
                            translatedBuffer.append('\n');
                        }
                        translatedBuffer.append(next);
                        translatedLineCount++;
                    }
                }
                runOnUiThread(() -> updateTranslatedTextView());
            }

            @Override
            public void onFailure(int[] reasons, long value) {
            }
        });
    }

    private void updateTranslatedTextView() {
        if (!partialLine.isEmpty() && !translatedPartialLine.isEmpty()) {
            if (translatedBuffer.length() == 0) {
                translatedTextView.setText(translatedPartialLine);
            } else {
                translatedTextView.setText(translatedBuffer.toString() + "\n" + translatedPartialLine);
            }
        } else {
            translatedTextView.setText(translatedBuffer.toString());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            boolean granted = manager.checkPermissions();
            recordButton.setEnabled(granted);
            if (!granted) {
                Toast.makeText(this, "RECORD_AUDIO permission is required", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        controlExecutor.shutdownNow();
        translationExecutor.shutdownNow();
        if (manager != null) {
            manager.release();
            manager = null;
        }
        super.onDestroy();
    }
}