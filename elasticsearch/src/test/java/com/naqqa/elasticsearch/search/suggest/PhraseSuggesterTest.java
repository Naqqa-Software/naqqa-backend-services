package com.naqqa.elasticsearch.search.suggest;

import com.naqqa.elasticsearch.search.suggest.phrase.NGramLanguageModel;
import com.naqqa.elasticsearch.search.suggest.phrase.PhraseCandidate;
import com.naqqa.elasticsearch.search.suggest.phrase.PhraseSuggester;
import com.naqqa.elasticsearch.search.suggest.term.SuggestMode;
import com.naqqa.elasticsearch.search.suggest.term.TermSuggestOptions;
import com.naqqa.elasticsearch.search.suggest.term.TermSuggester;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

public final class PhraseSuggesterTest {

    private List<List<String>> sentences() {
        return List.of(
            List.of("quick", "brown", "fox", "jumps", "over", "the", "lazy", "dog"),
            List.of("quick", "brown", "fox", "runs", "fast"),
            List.of("the", "lazy", "dog", "sleeps", "all", "day"),
            List.of("quick", "brown", "fox", "jumps", "high"),
            List.of("the", "quick", "fox", "jumps", "over", "the", "lazy", "dog"),
            List.of("brown", "fox", "and", "lazy", "dog", "are", "friends")
        );
    }

    private List<String> vocabulary() {
        return sentences().stream().flatMap(List::stream).toList();
    }

    @Test
    public void correctsMultiWordPhraseUsingNGramScoring() {
        TermSuggester termSuggester = new TermSuggester(vocabulary());
        NGramLanguageModel model = new NGramLanguageModel(sentences(), 0.1, 0.85);
        PhraseSuggester phraseSuggester = new PhraseSuggester(termSuggester, model);
        TermSuggestOptions options = new TermSuggestOptions().suggestMode(SuggestMode.ALWAYS).maxEdits(2).prefixLength(1).minWordLength(1);
        List<PhraseCandidate> results = phraseSuggester.suggest("quikc brown fox jumps ofer the lasy dog", 3, options, null);
        Assert.assertTrue(!results.isEmpty(), "expected phrase corrections");
        String best = results.get(0).text();
        Assert.assertEquals("quick brown fox jumps over the lazy dog", best);
    }

    @Test
    public void collatePredicateFiltersOutInvalidCandidates() {
        TermSuggester termSuggester = new TermSuggester(vocabulary());
        NGramLanguageModel model = new NGramLanguageModel(sentences(), 0.1, 0.85);
        PhraseSuggester phraseSuggester = new PhraseSuggester(termSuggester, model);
        TermSuggestOptions options = new TermSuggestOptions().suggestMode(SuggestMode.ALWAYS).maxEdits(2).prefixLength(1).minWordLength(1);
        List<PhraseCandidate> results = phraseSuggester.suggest("quikc brown fox", 5, options, text -> !text.contains("quick"));
        for (PhraseCandidate c : results) {
            Assert.assertFalse(c.text().contains("quick"), c.text());
        }
    }
}
