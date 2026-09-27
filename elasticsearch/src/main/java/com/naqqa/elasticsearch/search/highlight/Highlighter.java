package com.naqqa.elasticsearch.search.highlight;

import java.util.List;

public interface Highlighter {

    List<String> highlight(HighlightRequest request, HitContext hit);
}
