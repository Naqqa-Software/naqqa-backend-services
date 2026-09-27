package com.naqqa.elasticsearch.analysis.registry;

import com.naqqa.elasticsearch.analysis.AnalysisContext;
import com.naqqa.elasticsearch.analysis.AnalysisSettings;
import com.naqqa.elasticsearch.analysis.TokenFilterFactory;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.analysis.filter.ASCIIFoldingFilter;
import com.naqqa.elasticsearch.analysis.filter.ApostropheFilter;
import com.naqqa.elasticsearch.analysis.filter.CJKBigramFilter;
import com.naqqa.elasticsearch.analysis.filter.CJKWidthFilter;
import com.naqqa.elasticsearch.analysis.filter.ClassicFilter;
import com.naqqa.elasticsearch.analysis.filter.DecimalDigitFilter;
import com.naqqa.elasticsearch.analysis.filter.DelimitedPayloadFilter;
import com.naqqa.elasticsearch.analysis.filter.DictionaryDecompounderFilter;
import com.naqqa.elasticsearch.analysis.filter.EdgeNGramTokenFilter;
import com.naqqa.elasticsearch.analysis.filter.ElisionFilter;
import com.naqqa.elasticsearch.analysis.filter.FingerprintFilter;
import com.naqqa.elasticsearch.analysis.filter.FlattenGraphFilter;
import com.naqqa.elasticsearch.analysis.filter.HyphenationDecompounderFilter;
import com.naqqa.elasticsearch.analysis.filter.KeepTypesFilter;
import com.naqqa.elasticsearch.analysis.filter.KeepWordFilter;
import com.naqqa.elasticsearch.analysis.filter.KeywordMarkerFilter;
import com.naqqa.elasticsearch.analysis.filter.LengthFilter;
import com.naqqa.elasticsearch.analysis.filter.LimitTokenCountFilter;
import com.naqqa.elasticsearch.analysis.filter.LowerCaseFilter;
import com.naqqa.elasticsearch.analysis.filter.MinHashFilter;
import com.naqqa.elasticsearch.analysis.filter.MultiplexerFilter;
import com.naqqa.elasticsearch.analysis.filter.NGramTokenFilter;
import com.naqqa.elasticsearch.analysis.filter.PatternCaptureGroupFilter;
import com.naqqa.elasticsearch.analysis.filter.PatternReplaceFilter;
import com.naqqa.elasticsearch.analysis.filter.PhoneticFilter;
import com.naqqa.elasticsearch.analysis.filter.RemoveDuplicatesFilter;
import com.naqqa.elasticsearch.analysis.filter.ReverseStringFilter;
import com.naqqa.elasticsearch.analysis.filter.ShingleFilter;
import com.naqqa.elasticsearch.analysis.filter.StemmerFilter;
import com.naqqa.elasticsearch.analysis.filter.StemmerOverrideFilter;
import com.naqqa.elasticsearch.analysis.filter.StopFilter;
import com.naqqa.elasticsearch.analysis.filter.TrimFilter;
import com.naqqa.elasticsearch.analysis.filter.TruncateFilter;
import com.naqqa.elasticsearch.analysis.filter.UniqueFilter;
import com.naqqa.elasticsearch.analysis.filter.UpperCaseFilter;
import com.naqqa.elasticsearch.analysis.filter.worddelimiter.WordDelimiterFilter;
import com.naqqa.elasticsearch.analysis.filter.worddelimiter.WordDelimiterGraphFilter;
import com.naqqa.elasticsearch.analysis.filter.worddelimiter.WordDelimiterSettings;
import com.naqqa.elasticsearch.analysis.hyphenation.HyphenationTree;
import com.naqqa.elasticsearch.analysis.phonetic.PhoneticEncoders;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.analysis.stem.Stemmers;
import com.naqqa.elasticsearch.analysis.stop.StopwordLists;
import com.naqqa.elasticsearch.analysis.synonym.SynonymFilter;
import com.naqqa.elasticsearch.analysis.synonym.SynonymGraphFilter;
import com.naqqa.elasticsearch.analysis.synonym.SynonymMap;
import com.naqqa.elasticsearch.analysis.tokenizer.EdgeNGramTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.NGramTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.TokenChars;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public final class TokenFilterFactories {

    private TokenFilterFactories() {
    }

    public static TokenFilterFactory create(String name, String type, AnalysisSettings settings, AnalysisContext context,
                                              Function<String, TokenFilterFactory> filterLookup) {
        switch (type) {
            case "lowercase": {
                String lang = settings.getString("language", "");
                LowerCaseFilter.Variant variant = switch (lang.toLowerCase(Locale.ROOT)) {
                    case "greek" -> LowerCaseFilter.Variant.GREEK;
                    case "irish" -> LowerCaseFilter.Variant.IRISH;
                    case "turkish" -> LowerCaseFilter.Variant.TURKISH;
                    default -> LowerCaseFilter.Variant.DEFAULT;
                };
                return of(name, true, in -> new LowerCaseFilter(in, variant));
            }
            case "uppercase":
                return of(name, true, UpperCaseFilter::new);
            case "asciifolding": {
                boolean preserve = settings.getBoolean("preserve_original", false);
                return of(name, true, in -> new ASCIIFoldingFilter(in, preserve));
            }
            case "trim":
                return of(name, true, TrimFilter::new);
            case "truncate": {
                int length = settings.getInt("length", 10);
                return of(name, in -> new TruncateFilter(in, length));
            }
            case "length": {
                int min = settings.getInt("min", 0);
                int max = settings.getInt("max", Integer.MAX_VALUE);
                return of(name, in -> new LengthFilter(in, min, max));
            }
            case "unique": {
                boolean onlySamePos = settings.getBoolean("only_on_same_position", false);
                return of(name, in -> new UniqueFilter(in, onlySamePos));
            }
            case "reverse":
                return of(name, true, ReverseStringFilter::new);
            case "stop": {
                List<String> stopwords = resolveStopwords(settings, context, "_english_");
                boolean ignoreCase = settings.getBoolean("ignore_case", false);
                boolean removeTrailing = settings.getBoolean("remove_trailing", true);
                return of(name, in -> new StopFilter(in, stopwords, ignoreCase, removeTrailing));
            }
            case "keyword_marker": {
                List<String> words = context.getWordList(settings, "keywords");
                Set<String> set = words == null ? Set.of() : new HashSet<>(words);
                Pattern pattern = settings.has("keywords_pattern") ? Pattern.compile(settings.getString("keywords_pattern")) : null;
                boolean ignoreCase = settings.getBoolean("ignore_case", false);
                Set<String> finalSet = ignoreCase ? lower(set) : set;
                return of(name, in -> new KeywordMarkerFilter(in, finalSet, pattern));
            }
            case "stemmer": {
                String lang = settings.getString("language", settings.getString("name", "english"));
                Stemmer stemmer = Stemmers.create(lang);
                return of(name, in -> new StemmerFilter(in, stemmer));
            }
            case "porter_stem":
                return of(name, in -> new StemmerFilter(in, Stemmers.create("porter")));
            case "kstem":
                return of(name, in -> new StemmerFilter(in, Stemmers.create("kstem")));
            case "snowball": {
                String lang = settings.getString("language", "English").toLowerCase(Locale.ROOT);
                Stemmer stemmer = Stemmers.create(lang);
                return of(name, in -> new StemmerFilter(in, stemmer));
            }
            case "stemmer_override": {
                List<String> rules = context.getWordList(settings, "rules");
                Map<String, String> overrides = StemmerOverrideFilter.parseRules(rules);
                return of(name, in -> new StemmerOverrideFilter(in, overrides));
            }
            case "synonym":
            case "synonym_graph": {
                List<String> rules = context.getWordList(settings, "synonyms");
                boolean expand = settings.getBoolean("expand", true);
                boolean lenient = settings.getBoolean("lenient", false);
                boolean wordnet = "wordnet".equals(settings.getString("format", "solr"));
                SynonymMap map = SynonymMap.build(rules, expand, lenient, wordnet);
                if (type.equals("synonym")) {
                    return of(name, in -> new SynonymFilter(in, map));
                }
                return of(name, in -> new SynonymGraphFilter(in, map));
            }
            case "word_delimiter":
            case "word_delimiter_graph": {
                int flags = WordDelimiterSettings.flags(settings.asMap());
                List<String> typeTable = context.getWordList(settings, "type_table");
                byte[] table = WordDelimiterSettings.parseTypeTable(typeTable);
                List<String> protectedWords = context.getWordList(settings, "protected_words");
                Set<String> protSet = WordDelimiterSettings.protectedWords(protectedWords);
                if (type.equals("word_delimiter")) {
                    return of(name, in -> new WordDelimiterFilter(in, table, flags, protSet));
                }
                boolean adjustOffsets = WordDelimiterSettings.adjustOffsets(settings.asMap());
                return of(name, in -> new WordDelimiterGraphFilter(in, adjustOffsets, table, flags, protSet));
            }
            case "shingle": {
                int min = settings.getInt("min_shingle_size", 2);
                int max = settings.getInt("max_shingle_size", 2);
                boolean outputUnigrams = settings.getBoolean("output_unigrams", true);
                boolean outputUnigramsIfNoShingles = settings.getBoolean("output_unigrams_if_no_shingles", false);
                String sep = settings.getString("token_separator", " ");
                String filler = settings.getString("filler_token", "_");
                return of(name, in -> new ShingleFilter(in, min, max, outputUnigrams, outputUnigramsIfNoShingles, sep, filler));
            }
            case "ngram": {
                int min = settings.getInt("min_gram", 1);
                int max = settings.getInt("max_gram", 2);
                return of(name, in -> new NGramTokenFilter(in, min, max));
            }
            case "edge_ngram": {
                int min = settings.getInt("min_gram", 1);
                int max = settings.getInt("max_gram", 2);
                boolean preserveOriginal = settings.getBoolean("preserve_original", false);
                return of(name, in -> new EdgeNGramTokenFilter(in, min, max, preserveOriginal));
            }
            case "pattern_capture": {
                List<String> patternStrings = settings.getList("patterns");
                boolean preserveOriginal = settings.getBoolean("preserve_original", true);
                List<Pattern> patterns = new ArrayList<>();
                if (patternStrings != null) {
                    for (String p : patternStrings) {
                        patterns.add(Pattern.compile(p));
                    }
                }
                return of(name, in -> new PatternCaptureGroupFilter(in, patterns, preserveOriginal));
            }
            case "pattern_replace": {
                Pattern pattern = Pattern.compile(settings.getString("pattern"));
                String replacement = settings.getString("replacement", "");
                boolean all = settings.getBoolean("all", true);
                return of(name, true, in -> new PatternReplaceFilter(in, pattern, replacement, all));
            }
            case "elision": {
                List<String> articlesList = context.getWordList(settings, "articles");
                Set<String> articles = new HashSet<>(articlesList != null ? articlesList : defaultFrenchArticles());
                return of(name, true, in -> new ElisionFilter(in, articles));
            }
            case "apostrophe":
                return of(name, true, ApostropheFilter::new);
            case "decimal_digit":
                return of(name, true, DecimalDigitFilter::new);
            case "fingerprint": {
                char sep = settings.getString("separator", " ").charAt(0);
                int maxOutputSize = settings.getInt("max_output_size", 255);
                return of(name, in -> new FingerprintFilter(in, sep, maxOutputSize));
            }
            case "min_hash": {
                int hashCount = settings.getInt("hash_count", 1);
                int bucketCount = settings.getInt("bucket_count", 512);
                int hashSetSize = settings.getInt("hash_set_size", 1);
                boolean withRotation = settings.getBoolean("with_rotation", bucketCount > 1);
                return of(name, in -> new MinHashFilter(in, hashCount, bucketCount, hashSetSize, withRotation));
            }
            case "cjk_bigram": {
                List<String> ignored = settings.getList("ignored_scripts");
                Set<String> ignoreSet = ignored == null ? Set.of() : new HashSet<>(ignored);
                boolean outputUnigrams = settings.getBoolean("output_unigrams", false);
                boolean han = !ignoreSet.contains("han");
                boolean hiragana = !ignoreSet.contains("hiragana");
                boolean katakana = !ignoreSet.contains("katakana");
                boolean hangul = !ignoreSet.contains("hangul");
                return of(name, in -> new CJKBigramFilter(in, han, hiragana, katakana, hangul, outputUnigrams));
            }
            case "cjk_width":
                return of(name, true, CJKWidthFilter::new);
            case "classic":
                return of(name, true, ClassicFilter::new);
            case "delimited_payload": {
                char delimiter = settings.getString("delimiter", "|").charAt(0);
                DelimitedPayloadFilter.Encoding encoding = switch (settings.getString("encoding", "float")) {
                    case "identity" -> DelimitedPayloadFilter.Encoding.IDENTITY;
                    case "int" -> DelimitedPayloadFilter.Encoding.INT;
                    default -> DelimitedPayloadFilter.Encoding.FLOAT;
                };
                return of(name, in -> new DelimitedPayloadFilter(in, delimiter, encoding));
            }
            case "keep": {
                List<String> words = context.getWordList(settings, "keep_words");
                boolean ignoreCase = settings.getBoolean("keep_words_case", false);
                return of(name, in -> new KeepWordFilter(in, words == null ? List.of() : words, ignoreCase));
            }
            case "keep_types": {
                List<String> types = settings.getList("types");
                boolean exclude = "exclude".equals(settings.getString("mode", "include"));
                return of(name, in -> new KeepTypesFilter(in, types == null ? List.of() : types, exclude));
            }
            case "limit": {
                int maxTokenCount = settings.getInt("max_token_count", 1);
                boolean consumeAll = settings.getBoolean("consume_all_tokens", false);
                return of(name, in -> new LimitTokenCountFilter(in, maxTokenCount, consumeAll));
            }
            case "remove_duplicates":
                return of(name, RemoveDuplicatesFilter::new);
            case "flatten_graph":
                return of(name, FlattenGraphFilter::new);
            case "multiplexer": {
                List<String> filterNames = settings.getList("filters");
                boolean preserveOriginal = settings.getBoolean("preserve_original", true);
                List<Function<TokenStream, TokenStream>> branches = new ArrayList<>();
                if (filterNames != null) {
                    for (String chainSpec : filterNames) {
                        String[] parts = chainSpec.split(",");
                        List<TokenFilterFactory> chain = new ArrayList<>();
                        for (String p : parts) {
                            chain.add(filterLookup.apply(p.trim()));
                        }
                        branches.add(in -> {
                            TokenStream s = in;
                            for (TokenFilterFactory f : chain) {
                                s = f.create(s);
                            }
                            return s;
                        });
                    }
                }
                return of(name, in -> new MultiplexerFilter(in, branches, preserveOriginal));
            }
            case "dictionary_decompounder": {
                List<String> words = context.getWordList(settings, "word_list");
                Set<String> dict = new HashSet<>(words != null ? words : List.of());
                int minWordSize = settings.getInt("min_word_size", 5);
                int minSubwordSize = settings.getInt("min_subword_size", 2);
                int maxSubwordSize = settings.getInt("max_subword_size", 15);
                boolean onlyLongest = settings.getBoolean("only_longest_match", false);
                return of(name, in -> new DictionaryDecompounderFilter(in, dict, minWordSize, minSubwordSize, maxSubwordSize, onlyLongest));
            }
            case "hyphenation_decompounder": {
                String path = settings.getString("hyphenation_patterns_path");
                HyphenationTree tree = loadHyphenationTree(context, path);
                List<String> words = context.getWordList(settings, "word_list");
                Set<String> dict = words == null ? null : new HashSet<>(words);
                int minWordSize = settings.getInt("min_word_size", 5);
                int minSubwordSize = settings.getInt("min_subword_size", 2);
                int maxSubwordSize = settings.getInt("max_subword_size", 15);
                boolean onlyLongest = settings.getBoolean("only_longest_match", false);
                return of(name, in -> new HyphenationDecompounderFilter(in, tree, dict, minWordSize, minSubwordSize, maxSubwordSize, onlyLongest));
            }
            case "phonetic": {
                String encoderName = settings.getString("encoder", "metaphone");
                Integer maxCodeLen = settings.getInteger("max_code_len");
                boolean replace = settings.getBoolean("replace", true);
                return of(name, in -> new PhoneticFilter(in, PhoneticEncoders.create(encoderName, maxCodeLen), replace));
            }
            default:
                throw new IllegalArgumentException("Unknown filter type [" + type + "]");
        }
    }

    private static HyphenationTree loadHyphenationTree(AnalysisContext context, String path) {
        if (path == null) {
            throw new IllegalArgumentException("hyphenation_decompounder requires hyphenation_patterns_path");
        }
        try {
            return HyphenationTree.load(context.resolve(path));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to load hyphenation patterns from [" + path + "]", e);
        }
    }

    private static Set<String> lower(Set<String> set) {
        Set<String> out = new HashSet<>();
        for (String s : set) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static List<String> defaultFrenchArticles() {
        return List.of("l", "m", "t", "qu", "n", "s", "j", "d", "c", "jusqu", "quoiqu", "lorsqu", "puisqu");
    }

    public static List<String> resolveStopwords(AnalysisSettings settings, AnalysisContext context, String defaultList) {
        String pathKey = "stopwords_path";
        if (settings.has(pathKey)) {
            return context.readLines(settings.getString(pathKey), true);
        }
        Object raw = settings.get("stopwords");
        if (raw == null) {
            return StopwordLists.get(defaultList);
        }
        if (raw instanceof String s && StopwordLists.isKnown(s)) {
            return StopwordLists.get(s);
        }
        List<String> list = settings.getList("stopwords");
        List<String> out = new ArrayList<>();
        for (String w : list) {
            if (StopwordLists.isKnown(w)) {
                out.addAll(StopwordLists.get(w));
            } else {
                out.add(w);
            }
        }
        return out;
    }

    private static TokenFilterFactory of(String name, UnaryOperator<TokenStream> fn) {
        return of(name, false, fn);
    }

    private static TokenFilterFactory of(String name, boolean normalizing, UnaryOperator<TokenStream> fn) {
        return TokenFilterFactory.of(name, normalizing, fn);
    }
}
