package com.naqqa.elasticsearch.search.highlight;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.codec.termvectors.TermVectorTerm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MatchExtractor {

    private MatchExtractor() {
    }

    static List<Match> fromAnalysis(Analyzer analyzer, String field, String text, HighlightRequest request) {
        List<Match> matches = new ArrayList<>();
        if (analyzer == null || text == null) {
            return matches;
        }
        TokenStream ts = analyzer.tokenStream(field, text);
        try {
            ts.reset();
            while (ts.incrementToken()) {
                Token token = ts.token();
                String term = token.term().toLowerCase(Locale.ROOT);
                if (request.terms().contains(term)) {
                    matches.add(new Match(token.startOffset(), token.endOffset(), term));
                }
            }
            ts.end();
        } finally {
            ts.close();
        }
        matches.addAll(fromPatterns(text, request.phrases()));
        matches.sort((a, b) -> a.start() != b.start() ? Integer.compare(a.start(), b.start()) : Integer.compare(a.end(), b.end()));
        return matches;
    }

    static List<Match> fromTermVectors(List<TermVectorTerm> termVectors, HighlightRequest request, String fallbackText) {
        List<Match> matches = new ArrayList<>();
        if (termVectors == null) {
            return matches;
        }
        for (TermVectorTerm tv : termVectors) {
            if (tv.startOffsets() == null || tv.endOffsets() == null) {
                continue;
            }
            String term = new String(tv.term(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (!request.terms().contains(term)) {
                continue;
            }
            for (int i = 0; i < tv.freq(); i++) {
                matches.add(new Match(tv.startOffsets()[i], tv.endOffsets()[i], term, tv.freq()));
            }
        }
        if (fallbackText != null) {
            matches.addAll(fromPatterns(fallbackText, request.phrases()));
        }
        matches.sort((a, b) -> a.start() != b.start() ? Integer.compare(a.start(), b.start()) : Integer.compare(a.end(), b.end()));
        return matches;
    }

    static boolean hasOffsets(List<TermVectorTerm> termVectors) {
        if (termVectors == null || termVectors.isEmpty()) {
            return false;
        }
        for (TermVectorTerm tv : termVectors) {
            if (tv.startOffsets() != null && tv.endOffsets() != null) {
                return true;
            }
        }
        return false;
    }

    static List<Match> fromPatterns(String text, List<Pattern> patterns) {
        List<Match> matches = new ArrayList<>();
        for (Pattern p : patterns) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                if (m.end() > m.start()) {
                    matches.add(new Match(m.start(), m.end(), m.group()));
                }
            }
        }
        return matches;
    }
}
