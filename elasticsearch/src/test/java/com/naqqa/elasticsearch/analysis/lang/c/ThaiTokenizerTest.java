package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ThaiTokenizerTest {

    private List<String> drain(String text) {
        ThaiTokenizer t = new ThaiTokenizer();
        t.setInput(text);
        List<String> out = new ArrayList<>();
        t.reset();
        while (t.incrementToken()) {
            out.add(t.token().term());
        }
        t.end();
        t.close();
        return out;
    }

    @Test
    public void segmentsSimpleDictionarySentence() {
        Assert.assertEquals(List.of("ผม", "ชอบ", "กิน", "ข้าว"), drain("ผมชอบกินข้าว"));
    }

    @Test
    public void segmentsPronounVerbObjectSentence() {
        Assert.assertEquals(List.of("ฉัน", "รัก", "เพื่อน"), drain("ฉันรักเพื่อน"));
    }

    @Test
    public void segmentsGreetingWord() {
        Assert.assertEquals(List.of("สวัสดี"), drain("สวัสดี"));
    }

    @Test
    public void passesThroughNonThaiRunsAsWords() {
        Assert.assertEquals(List.of("Hello", "World"), drain("Hello World"));
    }

    @Test
    public void switchesBetweenThaiAndLatinRunsWithoutSpace() {
        Assert.assertEquals(List.of("ผม", "ชื่อ", "John"), drain("ผมชื่อJohn"));
    }

    @Test
    public void skipsWhitespaceBetweenThaiWords() {
        Assert.assertEquals(List.of("บ้าน", "ของ", "ผม"), drain("บ้าน ของ ผม"));
    }

    @Test
    public void fallsBackToClusterForUnknownThaiSequence() {
        List<String> terms = drain("เกกเกก");
        Assert.assertTrue(!terms.isEmpty());
        StringBuilder rejoined = new StringBuilder();
        for (String term : terms) {
            rejoined.append(term);
        }
        Assert.assertEquals("เกกเกก", rejoined.toString());
    }

    @Test
    public void thaiAnalyzerRemovesStopwordsFromFullSentence() {
        Analyzer analyzer = ThaiAnalyzerFactory.create(Map.of());
        List<String> terms = analyzer.analyze(null, "ผมชอบกินข้าวและก็ชอบดื่มน้ำ");
        Assert.assertTrue(terms.contains("ผม"));
        Assert.assertTrue(terms.contains("ชอบ"));
        Assert.assertTrue(terms.contains("กิน"));
        Assert.assertFalse(terms.contains("และ"));
        Assert.assertFalse(terms.contains("ก็"));
    }

    @Test
    public void dictionaryHasReasonableSize() {
        Assert.assertTrue(ThaiDictionary.size() >= 150);
    }
}
