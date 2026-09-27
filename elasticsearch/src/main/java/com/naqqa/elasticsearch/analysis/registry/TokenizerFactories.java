package com.naqqa.elasticsearch.analysis.registry;

import com.naqqa.elasticsearch.analysis.AnalysisContext;
import com.naqqa.elasticsearch.analysis.AnalysisSettings;
import com.naqqa.elasticsearch.analysis.Tokenizer;
import com.naqqa.elasticsearch.analysis.TokenizerFactory;
import com.naqqa.elasticsearch.analysis.tokenizer.CharGroupTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.ClassicTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.EdgeNGramTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.KeywordTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.LetterTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.LowerCaseTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.NGramTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.PathHierarchyTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.PatternTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.SimplePatternSplitTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.SimplePatternTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.StandardTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.TokenChars;
import com.naqqa.elasticsearch.analysis.tokenizer.UaxUrlEmailTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.WhitespaceTokenizer;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class TokenizerFactories {

    private TokenizerFactories() {
    }

    public static TokenizerFactory create(String name, String type, AnalysisSettings settings, AnalysisContext context) {
        switch (type) {
            case "standard":
                return of(name, () -> new StandardTokenizer(settings.getInt("max_token_length", StandardTokenizer.DEFAULT_MAX_TOKEN_LENGTH)));
            case "whitespace":
                return of(name, () -> new WhitespaceTokenizer(settings.getInt("max_token_length", 255)));
            case "letter":
                return of(name, LetterTokenizer::new);
            case "lowercase":
                return of(name, LowerCaseTokenizer::new);
            case "keyword":
                return of(name, () -> new KeywordTokenizer(settings.getInt("buffer_size", 256)));
            case "pattern": {
                Pattern pattern = Pattern.compile(settings.getString("pattern", "\\W+"));
                int group = settings.getInt("group", -1);
                return of(name, () -> new PatternTokenizer(pattern, group));
            }
            case "simple_pattern": {
                Pattern pattern = Pattern.compile(settings.getString("pattern", ""));
                return of(name, () -> new SimplePatternTokenizer(pattern));
            }
            case "simple_pattern_split": {
                Pattern pattern = Pattern.compile(settings.getString("pattern", ""));
                return of(name, () -> new SimplePatternSplitTokenizer(pattern));
            }
            case "char_group": {
                List<String> chars = settings.getList("tokenize_on_chars");
                java.util.Set<Integer> set = new java.util.HashSet<>();
                if (chars != null) {
                    for (String c : chars) {
                        set.addAll(expandCharClass(c));
                    }
                }
                int maxLen = settings.getInt("max_token_length", 255);
                return of(name, () -> new CharGroupTokenizer(set, maxLen));
            }
            case "ngram": {
                int min = settings.getInt("min_gram", 1);
                int max = settings.getInt("max_gram", 2);
                List<String> tokenChars = settings.getList("token_chars");
                String custom = settings.getString("custom_token_chars");
                NGramTokenizer.CodepointMatcher matcher = TokenChars.build(tokenChars, custom);
                return of(name, () -> new NGramTokenizer(min, max, matcher));
            }
            case "edge_ngram": {
                int min = settings.getInt("min_gram", 1);
                int max = settings.getInt("max_gram", 2);
                List<String> tokenChars = settings.getList("token_chars");
                String custom = settings.getString("custom_token_chars");
                NGramTokenizer.CodepointMatcher matcher = TokenChars.build(tokenChars, custom);
                return of(name, () -> new EdgeNGramTokenizer(min, max, matcher));
            }
            case "path_hierarchy": {
                char delimiter = firstChar(settings.getString("delimiter", "/"));
                char replacement = settings.has("replacement") ? firstChar(settings.getString("replacement")) : delimiter;
                int skip = settings.getInt("skip", 0);
                boolean reverse = settings.getBoolean("reverse", false);
                int bufferSize = settings.getInt("buffer_size", 1024);
                return of(name, () -> new PathHierarchyTokenizer(delimiter, replacement, skip, reverse, bufferSize));
            }
            case "uax_url_email":
                return of(name, () -> new UaxUrlEmailTokenizer(settings.getInt("max_token_length", StandardTokenizer.DEFAULT_MAX_TOKEN_LENGTH)));
            case "classic":
                return of(name, () -> new ClassicTokenizer(settings.getInt("max_token_length", 255)));
            default:
                throw new IllegalArgumentException("Unknown tokenizer type [" + type + "]");
        }
    }

    private static Set<Integer> expandCharClass(String c) {
        Set<Integer> out = new java.util.HashSet<>();
        switch (c) {
            case "whitespace" -> {
                for (int i = 0; i <= 0x20; i++) {
                    if (Character.isWhitespace(i)) {
                        out.add(i);
                    }
                }
            }
            case "letter" -> {
                for (int i = 0; i < 0x2500; i++) {
                    if (Character.isLetter(i)) {
                        out.add(i);
                    }
                }
            }
            case "digit" -> {
                for (int i = '0'; i <= '9'; i++) {
                    out.add(i);
                }
            }
            case "punctuation" -> {
                for (int i = 0; i < 0x2500; i++) {
                    int type = Character.getType(i);
                    if (type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                        || type == Character.END_PUNCTUATION || type == Character.START_PUNCTUATION
                        || type == Character.OTHER_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION
                        || type == Character.FINAL_QUOTE_PUNCTUATION) {
                        out.add(i);
                    }
                }
            }
            case "symbol" -> {
                for (int i = 0; i < 0x2500; i++) {
                    int type = Character.getType(i);
                    if (type == Character.CURRENCY_SYMBOL || type == Character.MATH_SYMBOL
                        || type == Character.MODIFIER_SYMBOL || type == Character.OTHER_SYMBOL) {
                        out.add(i);
                    }
                }
            }
            default -> out.add(c.codePointAt(0));
        }
        return out;
    }

    private static char firstChar(String s) {
        if (s.length() >= 2 && s.charAt(0) == '\\') {
            return switch (s.charAt(1)) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                default -> s.charAt(1);
            };
        }
        return s.charAt(0);
    }

    private static TokenizerFactory of(String name, java.util.function.Supplier<Tokenizer> supplier) {
        return new TokenizerFactory() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Tokenizer create() {
                return supplier.get();
            }
        };
    }
}
