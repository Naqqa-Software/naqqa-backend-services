package com.naqqa.elasticsearch.search.highlight;

import com.naqqa.elasticsearch.codec.termvectors.TermVectorTerm;

import java.util.List;

public final class FastVectorHighlighter implements Highlighter {

    @Override
    public List<String> highlight(HighlightRequest request, HitContext hit) {
        String text = hit.getSourceField(request.field());
        if (text == null) {
            return List.of();
        }
        List<TermVectorTerm> termVectors = hit.getTermVectors(request.field());
        if (termVectors == null) {
            throw new IllegalStateException("fvh highlighter requires term vectors for field [" + request.field() + "] but none were stored");
        }
        if (!MatchExtractor.hasOffsets(termVectors)) {
            throw new IllegalStateException("fvh highlighter requires offsets in term vectors for field [" + request.field() + "] but none were stored");
        }
        List<Match> matches = MatchExtractor.fromTermVectors(termVectors, request, text);
        return FragmentBuilder.buildFragments(text, matches, request);
    }
}
