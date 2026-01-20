package com.rp.rptranscription.lang;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class LanguageMapper {
    private static final Map<String, String> MAP = new HashMap<>();
    static {
        MAP.put("zh", "Chinese");
        MAP.put("zh-Hant", "Traditional Chinese");
        MAP.put("yue", "Cantonese");
        MAP.put("en", "English");
        MAP.put("fr", "French");
        MAP.put("pt", "Portuguese");
        MAP.put("es", "Spanish");
        MAP.put("ja", "Japanese");
        MAP.put("tr", "Turkish");
        MAP.put("ru", "Russian");
        MAP.put("ar", "Arabic");
        MAP.put("ko", "Korean");
        MAP.put("th", "Thai");
        MAP.put("it", "Italian");
        MAP.put("de", "German");
        MAP.put("vi", "Vietnamese");
        MAP.put("ms", "Malay");
        MAP.put("id", "Indonesian");
        MAP.put("tl", "Filipino");
        MAP.put("hi", "Hindi");
        MAP.put("pl", "Polish");
        MAP.put("cs", "Czech");
        MAP.put("nl", "Dutch");
        MAP.put("km", "Khmer");
        MAP.put("my", "Burmese");
        MAP.put("fa", "Persian");
        MAP.put("gu", "Gujarati");
        MAP.put("ur", "Urdu");
        MAP.put("te", "Telugu");
        MAP.put("mr", "Marathi");
        MAP.put("he", "Hebrew");
        MAP.put("bn", "Bengali");
        MAP.put("ta", "Tamil");
        MAP.put("uk", "Ukrainian");
        MAP.put("bo", "Tibetan");
        MAP.put("kk", "Kazakh");
        MAP.put("mn", "Mongolian");
        MAP.put("ug", "Uyghur");
    }

    public static String toLabel(String code) {
        if (code == null) return "English";
        String c = code.trim();
        String label = MAP.get(c);
        if (label != null) return label;
        Locale locale = Locale.forLanguageTag(c);
        String display = locale.getDisplayLanguage(Locale.ROOT);
        if (display == null || display.trim().isEmpty()) return c;
        return Character.toUpperCase(display.charAt(0)) + display.substring(1);
    }
}
