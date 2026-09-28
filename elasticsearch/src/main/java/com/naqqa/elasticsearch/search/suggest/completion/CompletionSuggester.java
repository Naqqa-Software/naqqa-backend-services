package com.naqqa.elasticsearch.search.suggest.completion;

import com.naqqa.elasticsearch.codec.fst.FST;
import com.naqqa.elasticsearch.codec.fst.FSTBuilder;
import com.naqqa.elasticsearch.codec.fst.FSTEnum;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class CompletionSuggester {

    private static final Comparator<CompletionEntry> ENTRY_ORDER =
        Comparator.comparingLong(CompletionEntry::weight).reversed()
            .thenComparing(CompletionEntry::insertionOrder);

    private final FST fst;
    private final String[] sortedTexts;
    private final List<List<CompletionEntry>> postings;

    public CompletionSuggester(List<CompletionEntry.Input> inputs) {
        int n = inputs.size();
        CompletionEntry[] entries = new CompletionEntry[n];
        long order = 0;
        for (int i = 0; i < n; i++) {
            CompletionEntry.Input input = inputs.get(i);
            entries[i] = new CompletionEntry(input.text(), input.weight(), input.contexts(), order++);
        }
        Arrays.sort(entries, (a, b) -> compareUtf8(a.text(), b.text()));

        List<String> texts = new ArrayList<>();
        List<List<CompletionEntry>> groups = new ArrayList<>();
        int i = 0;
        while (i < n) {
            String text = entries[i].text();
            List<CompletionEntry> group = new ArrayList<>(1);
            int j = i;
            while (j < n && entries[j].text().equals(text)) {
                group.add(entries[j]);
                j++;
            }
            group.sort(ENTRY_ORDER);
            texts.add(text);
            groups.add(group);
            i = j;
        }
        sortedTexts = texts.toArray(new String[0]);
        postings = groups;

        FSTBuilder builder = new FSTBuilder();
        try {
            for (int k = 0; k < sortedTexts.length; k++) {
                builder.add(sortedTexts[k].getBytes(StandardCharsets.UTF_8), k);
            }
            fst = builder.build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int compareUtf8(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public List<CompletionEntry> suggest(String prefix, int size, ContextQuery contextQuery) {
        List<CompletionEntry> results = new ArrayList<>();
        if (fst.isEmpty()) {
            return results;
        }
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        FSTEnum it = fst.iterator();
        boolean has = it.seekCeil(prefixBytes);
        while (has) {
            byte[] term = it.term();
            if (term == null || !startsWith(term, prefixBytes)) {
                break;
            }
            int idx = (int) it.output();
            for (CompletionEntry entry : postings.get(idx)) {
                if (contextQuery == null || contextQuery.matches(entry)) {
                    results.add(entry);
                }
            }
            has = it.next();
        }
        results.sort(ENTRY_ORDER);
        if (results.size() > size) {
            results = results.subList(0, size);
        }
        return results;
    }

    public List<CompletionEntry> suggestFuzzy(String prefix, int size, FuzzyOptions options, ContextQuery contextQuery) {
        List<CompletionEntry> results = new ArrayList<>();
        if (fst.isEmpty()) {
            return results;
        }
        byte[] requiredPrefixBytes = FuzzyPrefixMatcher.requiredPrefixBytes(prefix, options);
        FSTEnum it = fst.iterator();
        boolean has = it.seekCeil(requiredPrefixBytes);
        while (has) {
            byte[] term = it.term();
            if (term == null || !startsWith(term, requiredPrefixBytes)) {
                break;
            }
            int idx = (int) it.output();
            String text = sortedTexts[idx];
            if (FuzzyPrefixMatcher.matches(prefix, text, options)) {
                for (CompletionEntry entry : postings.get(idx)) {
                    if (contextQuery == null || contextQuery.matches(entry)) {
                        results.add(entry);
                    }
                }
            }
            has = it.next();
        }
        results.sort(ENTRY_ORDER);
        if (results.size() > size) {
            results = results.subList(0, size);
        }
        return results;
    }

    private static boolean startsWith(byte[] term, byte[] prefix) {
        if (term.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (term[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
