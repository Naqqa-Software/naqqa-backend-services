package com.naqqa.elasticsearch.analysis.tokenizer;

import com.naqqa.elasticsearch.analysis.Tokenizer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PatternTokenizer extends Tokenizer {

    private final Pattern pattern;
    private final int group;
    private final List<int[]> spans = new ArrayList<>();
    private int index;

    public PatternTokenizer(Pattern pattern, int group) {
        this.pattern = pattern;
        this.group = group;
    }

    @Override
    public void reset() {
        super.reset();
        spans.clear();
        index = 0;
        Matcher matcher = pattern.matcher(input);
        if (group >= 0) {
            while (matcher.find()) {
                if (matcher.start(group) < 0) {
                    continue;
                }
                if (matcher.start(group) == matcher.end(group)) {
                    continue;
                }
                spans.add(new int[]{matcher.start(group), matcher.end(group)});
            }
        } else {
            int last = 0;
            int len = input.length();
            int searchFrom = 0;
            while (searchFrom <= len && matcher.find(searchFrom)) {
                if (matcher.start() == matcher.end()) {
                    searchFrom = matcher.end() + 1;
                    continue;
                }
                spans.add(new int[]{last, matcher.start()});
                last = matcher.end();
                searchFrom = last;
            }
            spans.add(new int[]{last, len});
        }
    }

    @Override
    public boolean incrementToken() {
        if (index < spans.size()) {
            int[] span = spans.get(index++);
            token.clear();
            token.setTerm(input, span[0], span[1]);
            token.setOffset(correctOffset(span[0]), correctOffset(span[1]));
            return true;
        }
        return false;
    }
}
