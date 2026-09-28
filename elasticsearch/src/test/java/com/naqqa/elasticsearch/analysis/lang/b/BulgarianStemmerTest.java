package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

public final class BulgarianStemmerTest {

    private String stem(String word) {
        StringBuilder sb = new StringBuilder(word);
        new BulgarianStemmer().stem(sb);
        return sb.toString();
    }

    @Test
    public void stemsCommonVocabulary() {
        Assert.assertEquals("бърз", stem("бърз"));
        Assert.assertEquals("бърз", stem("бърза"));
        Assert.assertEquals("бърз", stem("бързата"));
        Assert.assertEquals("кафяв", stem("кафяв"));
        Assert.assertEquals("кафяв", stem("кафява"));
        Assert.assertEquals("лисиц", stem("лисица"));
        Assert.assertEquals("лисик", stem("лисици"));
        Assert.assertEquals("прескач", stem("прескача"));
        Assert.assertEquals("прескоч", stem("прескочи"));
        Assert.assertEquals("ленив", stem("ленив"));
        Assert.assertEquals("ленив", stem("ленивото"));
        Assert.assertEquals("куч", stem("куче"));
        Assert.assertEquals("куч", stem("кучета"));
        Assert.assertEquals("голям", stem("голям"));
        Assert.assertEquals("голям", stem("голяма"));
        Assert.assertEquals("радост", stem("радост"));
        Assert.assertEquals("радост", stem("радостта"));
        Assert.assertEquals("книг", stem("книга"));
        Assert.assertEquals("книг", stem("книги"));
        Assert.assertEquals("книг", stem("книгата"));
        Assert.assertEquals("момч", stem("момче"));
        Assert.assertEquals("момч", stem("момчета"));
        Assert.assertEquals("жен", stem("жена"));
    }

    @Test
    public void analyzerRemovesStopwordsAndStemsSentence() {
        Analyzer analyzer = BulgarianAnalyzerFactory.create(java.util.Map.of());
        List<String> terms = analyzer.analyze(null, "Бързата кафява лисица прескача ленивото куче и това беше голяма радост");
        Assert.assertFalse(terms.contains("и"));
        Assert.assertFalse(terms.contains("това"));
        Assert.assertFalse(terms.contains("беше"));
        Assert.assertTrue(terms.contains("бърз"));
        Assert.assertTrue(terms.contains("кафяв"));
        Assert.assertTrue(terms.contains("ленив"));
        Assert.assertTrue(terms.contains("голям"));
        Assert.assertTrue(terms.contains("радост"));
    }
}
