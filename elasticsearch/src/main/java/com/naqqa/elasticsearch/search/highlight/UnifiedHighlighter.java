package com.naqqa.elasticsearch.search.highlight;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.codec.termvectors.TermVectorTerm;

import java.util.List;

public final class UnifiedHighlighter implements Highlighter {

    @Override
    public List<String> highlight(HighlightRequest request, HitContext hit) {
        String text = hit.getSourceField(request.field());
        if (text == null) {
            return List.of();
        }
        List<TermVectorTerm> termVectors = hit.getTermVectors(request.field());
        List<Match> matches;
        if (MatchExtractor.hasOffsets(termVectors)) {
            matches = MatchExtractor.fromTermVectors(termVectors, request, text);
        } else {
            Analyzer analyzer = hit.getAnalyzer(request.field());
            matches = MatchExtractor.fromAnalysis(analyzer, request.field(), text, request);
        }
        return FragmentBuilder.buildFragments(text, matches, request);
    }
}
