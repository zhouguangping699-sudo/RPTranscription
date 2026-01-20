package com.rp.rptranscription.lang;

import org.junit.Assert;
import org.junit.Test;

public class LanguageMapperTest {
    @Test
    public void testMapperBasic() {
        Assert.assertEquals("Japanese", LanguageMapper.toLabel("ja"));
        Assert.assertEquals("Cantonese", LanguageMapper.toLabel("yue"));
        Assert.assertEquals("Traditional Chinese", LanguageMapper.toLabel("zh-Hant"));
        Assert.assertEquals("Chinese", LanguageMapper.toLabel("zh"));
    }
}
