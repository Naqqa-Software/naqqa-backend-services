package com.naqqa.elasticsearch.analysis.tokenizer;

import com.naqqa.elasticsearch.analysis.Tokenizer;

import java.util.ArrayList;
import java.util.List;

public final class PathHierarchyTokenizer extends Tokenizer {

    private final char delimiter;
    private final char replacement;
    private final int skip;
    private final boolean reverse;
    private final List<int[]> spans = new ArrayList<>();
    private int index;

    public PathHierarchyTokenizer(char delimiter, char replacement, int skip, boolean reverse, int bufferSize) {
        this.delimiter = delimiter;
        this.replacement = replacement;
        this.skip = skip;
        this.reverse = reverse;
    }

    @Override
    public void reset() {
        super.reset();
        spans.clear();
        index = 0;
        String text = input.toString();
        int len = text.length();
        List<Integer> boundaries = new ArrayList<>();
        for (int p = 1; p < len; p++) {
            if (text.charAt(p) == delimiter) {
                boundaries.add(p);
            }
        }
        boundaries.add(len);
        if (!reverse) {
            int startCut = 0;
            for (int i = 0; i < skip && i < boundaries.size() - 1; i++) {
                startCut = boundaries.get(i);
            }
            for (int i = skip; i < boundaries.size(); i++) {
                int end = boundaries.get(i);
                if (end > startCut) {
                    spans.add(new int[]{startCut, end});
                }
            }
        } else {
            int endCut = len;
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            starts.addAll(boundaries.subList(0, boundaries.size() - 1));
            for (int i = 0; i < skip && !starts.isEmpty(); i++) {
                endCut = starts.remove(starts.size() - 1);
            }
            for (int i = starts.size() - 1; i >= 0; i--) {
                int start = starts.get(i);
                if (start < endCut) {
                    spans.add(new int[]{start, endCut});
                }
            }
        }
    }

    @Override
    public boolean incrementToken() {
        if (index >= spans.size()) {
            return false;
        }
        int[] span = spans.get(index++);
        String text = input.toString().substring(span[0], span[1]);
        if (delimiter != replacement) {
            text = text.replace(delimiter, replacement);
        }
        token.clear();
        token.setTerm(text);
        token.setOffset(correctOffset(span[0]), correctOffset(span[1]));
        token.setPositionIncrement(index == 1 ? 1 : 0);
        return true;
    }
}
