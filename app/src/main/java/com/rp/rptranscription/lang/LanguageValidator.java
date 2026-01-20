package com.rp.rptranscription.lang;

import java.util.HashSet;
import java.util.Set;

public final class LanguageValidator {
    private static final Set<Character> CANTONESE_MARKERS = new HashSet<>();
    private static final Set<Character> TRADITIONAL_SAMPLES = new HashSet<>();
    static {
        for (char ch : new char[]{'嘅','咗','嚟','嗰','冇','唔','佢','咩','啦','喺','哋','噉','嘢'}) CANTONESE_MARKERS.add(ch);
        for (char ch : new char[]{'這','麼','裏','臺','與','雲','傳','國','萬','門','網','學','體','會','誰','發','愛','關','總','戰','車','電','醫'}) TRADITIONAL_SAMPLES.add(ch);
    }

    public static boolean isExpectedLanguage(String targetCode, String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String code = targetCode == null ? "" : targetCode.trim();
        if (code.equals("ja")) return containsJapanese(text) && !containsDevanagari(text);
        if (code.equals("mr")) return containsDevanagari(text);
        if (code.equals("yue")) return containsChinese(text) && (containsCantoneseMarkers(text) || containsTraditionalHints(text));
        if (code.equals("zh-Hant")) return containsChinese(text) && containsTraditionalHints(text);
        if (code.equals("zh")) return containsChinese(text);
        return true;
    }

    private static boolean containsJapanese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int block = Character.UnicodeBlock.of(ch) != null ? Character.UnicodeBlock.of(ch).hashCode() : 0;
            if (Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.HIRAGANA) return true;
            if (Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.KATAKANA) return true;
            if (Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) return true;
        }
        return false;
    }

    private static boolean containsDevanagari(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.DEVANAGARI) return true;
        }
        return false;
    }

    private static boolean containsChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            Character.UnicodeBlock b = Character.UnicodeBlock.of(ch);
            if (b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) return true;
        }
        return false;
    }

    private static boolean containsCantoneseMarkers(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (CANTONESE_MARKERS.contains(s.charAt(i))) return true;
        }
        return false;
    }

    private static boolean containsTraditionalHints(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (TRADITIONAL_SAMPLES.contains(s.charAt(i))) return true;
        }
        return false;
    }
}
