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
        java.util.Map<String, List<CompletionEntry>> byText = new java.util.LinkedHashMap<>();
        long order = 0;
        for (CompletionEntry.Input input : inputs) {
            CompletionEntry entry = new CompletionEntry(input.text(), input.weight(), input.contexts(), order++);
            byText.computeIfAbsent(input.text(), k -> new ArrayList<>()).add(entry);
        }
        List<String> texts = new ArrayList<>(byText.keySet());
        List<byte[]> byteKeys = new ArrayList<>();
        for (String t : texts) {
            byteKeys.add(t.getBytes(StandardCharsets.UTF_8));
        }
        Integer[] index = new Integer[texts.size()];
        for (int i = 0; i < index.length; i++) {
            index[i] = i;
        }
        Arrays.sort(index, (a, b) -> Arrays.compareUnsigned(byteKeys.get(a), byteKeys.get(b)));
        sortedTexts = new String[texts.size()];
        postings = new ArrayList<>(texts.size());
        for (int i = 0; i < index.length; i++) {
            sortedTexts[i] = texts.get(index[i]);
            postings.add(null);
        }
        FSTBuilder builder = new FSTBuilder();
        for (int i = 0; i < index.length; i++) {
            byte[] key = byteKeys.get(index[i]);
            builder.add(key, i);
            List<CompletionEntry> group = new ArrayList<>(byText.get(sortedTexts[i]));
            group.sort(ENTRY_ORDER);
            postings.set(i, group);
        }
        try {
            fst = builder.build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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
        for (int i = 0; i < sortedTexts.length; i++) {
            String text = sortedTexts[i];
            if (FuzzyPrefixMatcher.matches(prefix, text, options)) {
                for (CompletionEntry entry : postings.get(i)) {
                    if (contextQuery == null || contextQuery.matches(entry)) {
                        results.add(entry);
                    }
                }
            }
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
