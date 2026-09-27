package com.naqqa.elasticsearch.analysis;

import java.util.List;
import java.util.function.Function;

public interface TokenFilterFactory {

    String name();

    TokenStream create(TokenStream input);

    default boolean isNormalizing() {
        return false;
    }

    default TokenStream normalize(TokenStream input) {
        return isNormalizing() ? create(input) : input;
    }

    default TokenFilterFactory getChainAwareTokenFilterFactory(TokenizerFactory tokenizer, List<CharFilterFactory> charFilters,
                                                              List<TokenFilterFactory> previousTokenFilters,
                                                              Function<String, TokenFilterFactory> allFilters) {
        return this;
    }

    default TokenFilterFactory getSynonymFilter() {
        return this;
    }

    default boolean breaksFastVectorHighlighter() {
        return false;
    }

    TokenFilterFactory IDENTITY = new TokenFilterFactory() {
        @Override
        public String name() {
            return "identity";
        }

        @Override
        public TokenStream create(TokenStream input) {
            return input;
        }

        @Override
        public boolean isNormalizing() {
            return true;
        }
    };

    static TokenFilterFactory of(String name, Function<TokenStream, TokenStream> fn) {
        return of(name, false, fn);
    }

    static TokenFilterFactory of(String name, boolean normalizing, Function<TokenStream, TokenStream> fn) {
        return new TokenFilterFactory() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public TokenStream create(TokenStream input) {
                return fn.apply(input);
            }

            @Override
            public boolean isNormalizing() {
                return normalizing;
            }
        };
    }
}
