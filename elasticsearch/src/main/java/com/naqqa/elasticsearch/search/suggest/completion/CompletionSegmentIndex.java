package com.naqqa.elasticsearch.search.suggest.completion;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.codec.postings.PostingsEnum;
import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CompletionSegmentIndex {

    public record Match(int localDocId, long weight, String originalText) {
    }

    private static final CompletionSegmentIndex EMPTY =
        new CompletionSegmentIndex(new CompletionSuggester(List.of()), new int[0], new String[0]);

    private final CompletionSuggester suggester;
    private final int[] localDocIds;
    private final String[] originalTexts;

    private CompletionSegmentIndex(CompletionSuggester suggester, int[] localDocIds, String[] originalTexts) {
        this.suggester = suggester;
        this.localDocIds = localDocIds;
        this.originalTexts = originalTexts;
    }

    public static CompletionSegmentIndex build(SegmentReader segment, String field) throws IOException {
        TermsEnum te = segment.terms(field);
        if (te == null) {
            return EMPTY;
        }
        NumericDocValuesReader weights = segment.numericDocValues(field);
        FieldInfo info = segment.fieldInfo(field);
        int flags = info != null ? info.indexOptions() : PostingsFlags.FREQS;
        int maxDoc = segment.maxDoc();
        List<CompletionEntry.Input> inputs = new ArrayList<>();
        List<Integer> docIds = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        byte[] t;
        while ((t = te.next()) != null) {
            String original = new String(t, StandardCharsets.UTF_8);
            PostingsEnum postings = te.postings(flags);
            int doc;
            while ((doc = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (doc >= maxDoc) {
                    continue;
                }
                long weight = weights != null && weights.advanceExact(doc) ? weights.longValue() : 1L;
                inputs.add(new CompletionEntry.Input(original.toLowerCase(Locale.ROOT), weight));
                docIds.add(doc);
                texts.add(original);
            }
        }
        if (inputs.isEmpty()) {
            return EMPTY;
        }
        int[] localDocIds = new int[docIds.size()];
        for (int i = 0; i < localDocIds.length; i++) {
            localDocIds[i] = docIds.get(i);
        }
        return new CompletionSegmentIndex(new CompletionSuggester(inputs), localDocIds, texts.toArray(new String[0]));
    }

    public List<Match> suggest(String prefix, ContextQuery contextQuery) {
        List<Match> out = new ArrayList<>();
        for (CompletionEntry entry : suggester.suggest(prefix, Integer.MAX_VALUE, contextQuery)) {
            out.add(toMatch(entry));
        }
        return out;
    }

    public List<Match> suggestFuzzy(String prefix, FuzzyOptions options, ContextQuery contextQuery) {
        List<Match> out = new ArrayList<>();
        for (CompletionEntry entry : suggester.suggestFuzzy(prefix, Integer.MAX_VALUE, options, contextQuery)) {
            out.add(toMatch(entry));
        }
        return out;
    }

    private Match toMatch(CompletionEntry entry) {
        int idx = (int) entry.insertionOrder();
        return new Match(localDocIds[idx], entry.weight(), originalTexts[idx]);
    }
}
