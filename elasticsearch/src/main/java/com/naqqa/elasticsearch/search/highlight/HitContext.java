package com.naqqa.elasticsearch.search.highlight;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.codec.termvectors.TermVectorTerm;

import java.util.List;
import java.util.Map;

public interface HitContext {

    String getSourceField(String name);

    Map<String, Object> getSource();

    List<TermVectorTerm> getTermVectors(String field);

    Analyzer getAnalyzer(String field);
}
