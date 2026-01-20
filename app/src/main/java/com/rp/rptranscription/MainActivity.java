package com.rp.rptranscription;

import android.Manifest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.text.TextWatcher;
import android.text.Editable;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.widget.Toast;
import java.util.Locale;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.rp.mlkittranslator.TranslationManager;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;

    private Button recordButton;
    private TextView textView;
    private TextView targetLanguageValue;
    private TextView sourceLanguageValue;
    private TextView translatedTextView;
    private ProgressBar translationProgress;
    private TextView translationStatus;
    private VoiceRecognitionAndVadManager manager;
    private TranslationManager translationManager;

    private boolean isRunning = false;
    private volatile boolean isBusy = false;
    private final StringBuilder resultBuffer = new StringBuilder();
    private String partialLine = "";
    private final StringBuilder translatedBuffer = new StringBuilder();
    private int translatedLinesCount = 0;

    private String selectedTargetLanguageCode = "en";
    private String selectedSourceLanguageCode = "zh";

    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor();
    private final Handler translateHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingTranslate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        recordButton = findViewById(R.id.record_button);
        textView = findViewById(R.id.my_text);
        textView.setMovementMethod(new ScrollingMovementMethod());

        targetLanguageValue = findViewById(R.id.target_language_value);
        sourceLanguageValue = findViewById(R.id.source_language_value);
        translatedTextView = findViewById(R.id.translated_text);
        translatedTextView.setMovementMethod(new ScrollingMovementMethod());
        translationProgress = findViewById(R.id.translation_progress);
        translationStatus = findViewById(R.id.translation_status);

        updateTargetLanguageValue();
        updateSourceLanguageValue();
        updateTranslatedSubtitlePlaceholder(selectedTargetLanguageCode);
        targetLanguageValue.setOnClickListener(v -> showTargetLanguageDialog());
        sourceLanguageValue.setOnClickListener(v -> showSourceLanguageDialog());

        manager = VoiceRecognitionAndVadManager.getInstance(this);
        manager.setRecognitionCallback(new VoiceRecognitionAndVadManager.RecognitionCallback() {
            @Override
            public void onRecognitionResult(String result) {
                if (result == null || result.trim().isEmpty()) {
                    return;
                }
                partialLine = "";
                if (resultBuffer.length() > 0) {
                    resultBuffer.append('\n');
                }
                resultBuffer.append(result);
                textView.setText(resultBuffer.toString());
                scheduleAutoTranslate();
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
                    return;
                }

                if (resultBuffer.length() == 0) {
                    textView.setText(partialLine);
                } else {
                    textView.setText(resultBuffer.toString() + "\n" + partialLine);
                }
            }

            @Override
            public void onRecognitionError(String errorMsg) {
                Toast.makeText(MainActivity.this, errorMsg, Toast.LENGTH_SHORT).show();
            }
        });

        recordButton.setOnClickListener(v -> toggleRecognition());
        translationManager = TranslationManager.getInstance();
        translationManager.initialize(getApplicationContext());
        selectedSourceLanguageCode = manager.getMainLanguage().getCode();
        updateSourceLanguageValue();
        translationManager.setSourceLanguage(selectedSourceLanguageCode);
        translationManager.setTargetLanguage(selectedTargetLanguageCode);
        translationManager.checkModelAvailability((hasSrc, hasTgt) -> {
            if (!hasSrc || !hasTgt) {
                promptDownloadModels();
            }
        });
        textView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                scheduleAutoTranslate();
            }
        });

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
                    translationManager.setTargetLanguage(selectedTargetLanguageCode);
                    translationManager.checkModelAvailability((hasSrc, hasTgt) -> {
                        if (!hasSrc || !hasTgt) {
                            promptDownloadModels();
                        }
                    });
                }
        );
        dialog.show();
    }

    private void showSourceLanguageDialog() {
        LanguageSelectionDialog dialog = new LanguageSelectionDialog(
                this,
                R.raw.nllb_supported_languages,
                selectedSourceLanguageCode,
                code -> {
                    selectedSourceLanguageCode = code;
                    updateSourceLanguageValue();
                    manager.setMainLanguage(selectedSourceLanguageCode);
                    translationManager.setSourceLanguage(selectedSourceLanguageCode);
                    translationManager.checkModelAvailability((hasSrc, hasTgt) -> {
                        if (!hasSrc || !hasTgt) {
                            promptDownloadModels();
                        }
                    });
                }
        );
        dialog.show();
    }

    private void updateTargetLanguageValue() {
        String display = getDisplayNameForCode(selectedTargetLanguageCode);
        targetLanguageValue.setText(display + " (" + selectedTargetLanguageCode + ")");
    }

    private void updateSourceLanguageValue() {
        String display = getDisplayNameForCode(selectedSourceLanguageCode);
        sourceLanguageValue.setText(display + " (" + selectedSourceLanguageCode + ")");
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
            textView.setText("");
            translatedBuffer.setLength(0);
            translatedTextView.setText("");
            translatedLinesCount = 0;

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

    private void scheduleAutoTranslate() {
        if (pendingTranslate != null) {
            translateHandler.removeCallbacks(pendingTranslate);
        }
        pendingTranslate = () -> {
            if (partialLine != null && !partialLine.isEmpty()) {
                return;
            }
            String all = textView.getText() != null ? textView.getText().toString() : "";
            String[] lines = all.split("\\n");
            if (lines.length == 0) return;
            if (lines.length <= translatedLinesCount) return;
            String newLine = lines[lines.length - 1].trim();
            if (newLine.isEmpty()) return;
            translationStatus.setText("正在翻译…");
            translationProgress.setVisibility(android.view.View.VISIBLE);
            translationProgress.setIndeterminate(true);
            translationManager.translate(newLine, new TranslationManager.TranslateCallback() {
                @Override
                public void onSuccess(String t) {
                    if (translatedBuffer.length() > 0) {
                        translatedBuffer.append('\n');
                    }
                    translatedBuffer.append(t);
                    translatedTextView.setText(translatedBuffer.toString());
                    translationProgress.setVisibility(android.view.View.GONE);
                    translationStatus.setText("");
                    translatedLinesCount = lines.length;
                }
                @Override
                public void onFailed() {
                    translationProgress.setVisibility(android.view.View.GONE);
                    translationStatus.setText("翻译失败");
                }
            });
        };
        translateHandler.postDelayed(pendingTranslate, 500);
    }

    private void promptDownloadModels() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("下载翻译模型")
                .setMessage("首次使用需要下载所需模型，是否现在下载？")
                .setPositiveButton("现在下载", (d, w) -> {
                    translationStatus.setText("正在下载模型…");
                    translationProgress.setVisibility(android.view.View.VISIBLE);
                    translationProgress.setIndeterminate(true);
                    translationManager.downloadModel(true, new TranslationManager.DownloadCallback() {
                        @Override
                        public void onProgress(int percent, boolean indeterminate) {
                            translationProgress.setIndeterminate(indeterminate);
                            if (!indeterminate) {
                                translationProgress.setProgress(percent);
                            }
                        }
                        @Override
                        public void onCompleted() {
                            translationProgress.setVisibility(android.view.View.GONE);
                            translationStatus.setText("");
                            Toast.makeText(MainActivity.this, "模型下载完成", Toast.LENGTH_SHORT).show();
                        }
                        @Override
                        public void onFailed() {
                            translationProgress.setVisibility(android.view.View.GONE);
                            translationStatus.setText("模型下载失败");
                            Toast.makeText(MainActivity.this, "模型下载失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton("稍后", (d, w) -> {})
                .show();
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
        if (manager != null) {
            manager.release();
            manager = null;
        }
        super.onDestroy();
    }
}
