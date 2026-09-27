package com.naqqa.elasticsearch.search.highlight;

import com.naqqa.elasticsearch.analysis.Analyzer;

import java.util.List;

public final class PlainHighlighter implements Highlighter {

    @Override
    public List<String> highlight(HighlightRequest request, HitContext hit) {
        String text = hit.getSourceField(request.field());
        if (text == null) {
            return List.of();
        }
        Analyzer analyzer = hit.getAnalyzer(request.field());
        List<Match> matches = MatchExtractor.fromAnalysis(analyzer, request.field(), text, request);
        return FragmentBuilder.buildFragments(text, matches, request);
    }
}
