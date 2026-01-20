package com.rp.rptranscription;

import android.Manifest;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.TextView;
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
import com.rp.rptranscription.hymt.HyMtEngine;
import com.rp.rptranscription.lang.LanguageValidator;
import com.rp.rptranscription.translation.TextSynchronizer;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;

    private Button recordButton;
    private TextView textView;
    private TextView targetLanguageValue;
    private TextView translatedTextView;
    private VoiceRecognitionAndVadManager manager;

    private boolean isRunning = false;
    private volatile boolean isBusy = false;
    private final StringBuilder resultBuffer = new StringBuilder();
    private String partialLine = "";
    private final TextSynchronizer synchronizer = new TextSynchronizer();
    private final StringBuilder translatedBuffer = new StringBuilder();

    private String selectedTargetLanguageCode = "en";

    private HyMtEngine hyMtEngine;
    private final ExecutorService translationExecutor = Executors.newSingleThreadExecutor();

    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        recordButton = findViewById(R.id.record_button);
        textView = findViewById(R.id.my_text);
        textView.setMovementMethod(new ScrollingMovementMethod());

        targetLanguageValue = findViewById(R.id.target_language_value);
        translatedTextView = findViewById(R.id.translated_text);
        translatedTextView.setMovementMethod(new ScrollingMovementMethod());

        updateTargetLanguageValue();
        updateTranslatedSubtitlePlaceholder(selectedTargetLanguageCode);
        targetLanguageValue.setOnClickListener(v -> showTargetLanguageDialog());

        hyMtEngine = new HyMtEngine();
        int avail = Runtime.getRuntime().availableProcessors();
        hyMtEngine.setNThreads(Math.max(4, Math.min(avail, 8)));
        hyMtEngine.setNCtx(512);
        translationExecutor.execute(() -> {
            boolean ok = hyMtEngine.init();
            runOnUiThread(() -> {
                if (!ok) {
                    translatedTextView.setText("翻译引擎初始化失败（检查 /data/model/assets/llama/hunyuan-1.8b-q4_0.gguf 是否可读）");
                }
            });
        });

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
                synchronizer.appendFinalSource(result);

                requestTranslate(result);
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
                R.raw.hymt_supported_languages,
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
            textView.setText("");
            translatedTextView.setText("");
            translatedBuffer.setLength(0);
            synchronizer.reset();

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
        if (hyMtEngine != null) {
            hyMtEngine.release();
            hyMtEngine = null;
        }
        if (manager != null) {
            manager.release();
            manager = null;
        }
        super.onDestroy();
    }

    private void requestTranslate(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        HyMtEngine engine = hyMtEngine;
        if (engine == null || !engine.isReady()) {
            return;
        }

        runOnUiThread(() -> {
            if (translatedBuffer.length() == 0) {
                translatedTextView.setText("翻译中... (" + selectedTargetLanguageCode + ")");
            } else {
                translatedTextView.setText(translatedBuffer.toString() + "\n翻译中... (" + selectedTargetLanguageCode + ")");
            }
        });

        engine.translateAsync(trimmed, selectedTargetLanguageCode, new HyMtEngine.Callback() {
            @Override
            public void onResult(String translated) {
                boolean ok = LanguageValidator.isExpectedLanguage(selectedTargetLanguageCode, translated);
                String finalText = ok ? translated : "语言不匹配";
                if (ok) {
                    synchronizer.appendFinalTranslation(finalText);
                    if (translatedBuffer.length() > 0) translatedBuffer.append('\n');
                    translatedBuffer.append(finalText);
                } else {
                    if (translatedBuffer.length() > 0) translatedBuffer.append('\n');
                    translatedBuffer.append(finalText);
                }
                runOnUiThread(() -> translatedTextView.setText(translatedBuffer.toString()));
            }

            @Override
            public void onError(String message) {
                if (translatedBuffer.length() > 0) translatedBuffer.append('\n');
                translatedBuffer.append("翻译失败: ").append(message);
                runOnUiThread(() -> translatedTextView.setText(translatedBuffer.toString()));
            }
        });
    }
}
