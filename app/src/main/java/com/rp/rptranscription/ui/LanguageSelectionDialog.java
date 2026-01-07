package com.rp.rptranscription.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Xml;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.rp.rptranscription.R;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LanguageSelectionDialog {

    public interface OnLanguageSelectedListener {
        void onSelected(String languageCode);
    }

    private final Activity activity;
    private final int rawResId;
    private final String selectedCode;
    private final OnLanguageSelectedListener listener;

    private AlertDialog dialog;

    public LanguageSelectionDialog(Activity activity, int rawResId, String selectedCode, OnLanguageSelectedListener listener) {
        this.activity = activity;
        this.rawResId = rawResId;
        this.selectedCode = selectedCode;
        this.listener = listener;
    }

    public void show() {
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_languages, null);
        EditText searchInput = root.findViewById(R.id.search_input);
        RecyclerView list = root.findViewById(R.id.language_list);

        List<LanguageItem> allLanguages = loadLanguages(activity, rawResId);
        LanguageListAdapter adapter = new LanguageListAdapter(allLanguages, selectedCode, code -> {
            if (listener != null) {
                listener.onSelected(code);
            }
            if (dialog != null) {
                dialog.dismiss();
            }
        });

        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .create();
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }

        searchInput.clearFocus();
        list.requestFocus();
    }

    private static String getDisplayNameForCode(String code) {
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

    private static List<LanguageItem> loadLanguages(Context context, int rawResId) {
        List<LanguageItem> items = new ArrayList<>();
        try (InputStream is = context.getResources().openRawResource(rawResId)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(is, "utf-8");

            int eventType = parser.getEventType();
            boolean inCodeTag = false;

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    if ("code".equals(parser.getName())) {
                        inCodeTag = true;
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    if (inCodeTag) {
                        String code = parser.getText();
                        if (code != null) {
                            code = code.trim();
                            if (!code.isEmpty()) {
                                items.add(new LanguageItem(code, getDisplayNameForCode(code)));
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if ("code".equals(parser.getName())) {
                        inCodeTag = false;
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    static class LanguageItem {
        final String code;
        final String name;

        LanguageItem(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }
}
