package com.rp.rptranscription.lang;

import org.junit.Assert;
import org.junit.Test;

public class LanguageValidatorTest {
    @Test
    public void testJapaneseValidation() {
        String text = "これはテストです。";
        Assert.assertTrue(LanguageValidator.isExpectedLanguage("ja", text));
        Assert.assertFalse(LanguageValidator.isExpectedLanguage("mr", text));
    }

    @Test
    public void testMarathiValidation() {
        String text = "हे एक चाचणी आहे.";
        Assert.assertTrue(LanguageValidator.isExpectedLanguage("mr", text));
        Assert.assertFalse(LanguageValidator.isExpectedLanguage("ja", text));
    }

    @Test
    public void testCantoneseValidation() {
        String yue = "我唔知道佢喺邊度。";
        String zhcn = "我不知道他在哪里。";
        Assert.assertTrue(LanguageValidator.isExpectedLanguage("yue", yue));
        Assert.assertTrue(LanguageValidator.isExpectedLanguage("yue", zhcn));
    }

    @Test
    public void testTraditionalChineseValidation() {
        String zht = "這是一個測試。";
        String zhs = "这是一個测试。";
        Assert.assertTrue(LanguageValidator.isExpectedLanguage("zh-Hant", zht));
        Assert.assertFalse(LanguageValidator.isExpectedLanguage("zh-Hant", zhs));
    }
}
