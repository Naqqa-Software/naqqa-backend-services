package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.analysis.hyphenation.Hyphenation;
import com.naqqa.elasticsearch.analysis.hyphenation.HyphenationTree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class HyphenationDecompounderFilter extends TokenFilter {

    private final HyphenationTree tree;
    private final Set<String> dictionary;
    private final int minWordSize;
    private final int minSubwordSize;
    private final int maxSubwordSize;
    private final boolean onlyLongestMatch;
    private final Deque<Token> pending = new ArrayDeque<>();

    public HyphenationDecompounderFilter(TokenStream input, HyphenationTree tree, Set<String> dictionary, int minWordSize,
                                          int minSubwordSize, int maxSubwordSize, boolean onlyLongestMatch) {
        super(input);
        this.tree = tree;
        this.dictionary = dictionary;
        this.minWordSize = minWordSize;
        this.minSubwordSize = minSubwordSize;
        this.maxSubwordSize = maxSubwordSize;
        this.onlyLongestMatch = onlyLongestMatch;
    }

    @Override
    public void reset() {
        super.reset();
        pending.clear();
    }

    @Override
    public boolean incrementToken() {
        if (!pending.isEmpty()) {
            token.clear();
            token.copyFrom(pending.poll());
            token.setPositionIncrement(0);
            return true;
        }
        if (!input.incrementToken()) {
            return false;
        }
        String term = token.term();
        int len = term.length();
        if (len >= minWordSize) {
            Hyphenation hyph = tree.hyphenate(term, 1, 1);
            List<Integer> cuts = new ArrayList<>();
            cuts.add(0);
            if (hyph != null) {
                for (int p : hyph.getHyphenationPoints()) {
                    cuts.add(p);
                }
            }
            cuts.add(len);
            List<int[]> matches = new ArrayList<>();
            for (int a = 0; a < cuts.size(); a++) {
                for (int b = a + 1; b < cuts.size(); b++) {
                    int i = cuts.get(a);
                    int j = cuts.get(b);
                    int size = j - i;
                    if (size < minSubwordSize || size > maxSubwordSize) {
                        continue;
                    }
                    if (i == 0 && j == len) {
                        continue;
                    }
                    if (dictionary == null || dictionary.contains(term.substring(i, j))) {
                        matches.add(new int[]{i, j});
                    }
                }
            }
            if (onlyLongestMatch && matches.size() > 1) {
                int[] best = matches.get(0);
                for (int[] m : matches) {
                    if (m[1] - m[0] > best[1] - best[0]) {
                        best = m;
                    }
                }
                matches.clear();
                matches.add(best);
            }
            LinkedHashSet<String> emittedText = new LinkedHashSet<>();
            Token base = token.copy();
            for (int[] m : matches) {
                String sub = term.substring(m[0], m[1]);
                if (!emittedText.add(sub)) {
                    continue;
                }
                Token t = base.copy();
                t.setTerm(sub);
                t.setOffset(base.startOffset() + m[0], base.startOffset() + m[1]);
                pending.add(t);
            }
        }
        return true;
    }
}
