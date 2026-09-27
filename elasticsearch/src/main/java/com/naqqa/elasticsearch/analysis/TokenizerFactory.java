package com.naqqa.elasticsearch.analysis;

public interface TokenizerFactory {

    String name();

    Tokenizer create();
}
