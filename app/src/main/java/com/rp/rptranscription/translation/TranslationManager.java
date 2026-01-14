package com.rp.rptranscription.translation;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.rp.rptranscription.voice_translation.neural_networks.NeuralNetworkApi;
import com.rp.rptranscription.voice_translation.neural_networks.translation.Translator;

public final class TranslationManager {
    private final Context appContext;
    private final Object lock = new Object();

    @Nullable
    private Translator translator;
    private boolean initializing = false;

    public TranslationManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    public boolean isReady() {
        synchronized (lock) {
            return translator != null && !initializing;
        }
    }

    public void initIfNeeded(@NonNull NeuralNetworkApi.InitListener listener) {
        synchronized (lock) {
            if (translator != null) {
                listener.onInitializationFinished();
                return;
            }
            if (initializing) {
                return;
            }
            initializing = true;
        }

        final Translator[] holder = new Translator[1];
        holder[0] = new Translator(appContext, Translator.NLLB_CACHE, new NeuralNetworkApi.InitListener() {
            @Override
            public void onInitializationFinished() {
                synchronized (lock) {
                    translator = holder[0];
                    initializing = false;
                }
                listener.onInitializationFinished();
            }

            @Override
            public void onError(int[] reasons, long value) {
                synchronized (lock) {
                    initializing = false;
                }
                listener.onError(reasons, value);
            }
        });
    }

    @Nullable
    public Translator getTranslatorOrNull() {
        synchronized (lock) {
            return translator;
        }
    }
}
