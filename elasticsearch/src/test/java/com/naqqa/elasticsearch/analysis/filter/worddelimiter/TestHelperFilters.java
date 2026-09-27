package com.naqqa.elasticsearch.analysis.filter.worddelimiter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.Set;

final class TestHelperFilters {

    private TestHelperFilters() {
    }

    static final class LargePosInc extends TokenFilter {
        LargePosInc(TokenStream in) {
            super(in);
        }

        @Override
        public boolean incrementToken() {
            if (!input.incrementToken()) {
                return false;
            }
            if (token.termEquals("largegap") || token.termEquals("/")) {
                token.setPositionIncrement(10);
            }
            return true;
        }
    }

    static final class Stop extends TokenFilter {
        private final Set<String> stop;
        private int skipped;

        Stop(TokenStream in, Set<String> stop) {
            super(in);
            this.stop = stop;
        }

        @Override
        public boolean incrementToken() {
            skipped = 0;
            while (input.incrementToken()) {
                if (stop.contains(token.term())) {
                    skipped += token.positionIncrement();
                    continue;
                }
                token.setPositionIncrement(token.positionIncrement() + skipped);
                return true;
            }
            return false;
        }
    }

    static final class KeywordStartsWithK extends TokenFilter {
        KeywordStartsWithK(TokenStream in) {
            super(in);
        }

        @Override
        public boolean incrementToken() {
            if (!input.incrementToken()) {
                return false;
            }
            if (token.length() > 0 && token.charAt(0) == 'k') {
                token.setKeyword(true);
            }
            return true;
        }
    }
}
