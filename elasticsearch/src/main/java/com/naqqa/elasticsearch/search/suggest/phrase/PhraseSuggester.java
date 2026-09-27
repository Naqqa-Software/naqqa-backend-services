package com.naqqa.elasticsearch.search.suggest.phrase;

import com.naqqa.elasticsearch.search.suggest.term.TermSuggestOptions;
import com.naqqa.elasticsearch.search.suggest.term.TermSuggester;
import com.naqqa.elasticsearch.search.suggest.term.TermSuggestion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public final class PhraseSuggester {

    private final TermSuggester termSuggester;
    private final NGramLanguageModel model;
    private final int beamWidth;
    private final int candidatesPerToken;
    private final double realWordErrorPenalty;

    public PhraseSuggester(TermSuggester termSuggester, NGramLanguageModel model) {
        this(termSuggester, model, 5, 4, 0.9);
    }

    public PhraseSuggester(TermSuggester termSuggester, NGramLanguageModel model, int beamWidth, int candidatesPerToken, double realWordErrorPenalty) {
        this.termSuggester = termSuggester;
        this.model = model;
        this.beamWidth = beamWidth;
        this.candidatesPerToken = candidatesPerToken;
        this.realWordErrorPenalty = realWordErrorPenalty;
    }

    public List<PhraseCandidate> suggest(String phrase, int size, TermSuggestOptions termOptions, Predicate<String> collate) {
        String[] tokens = phrase.trim().toLowerCase(Locale.ROOT).split("\\s+");
        List<List<WordOption>> optionsPerToken = new ArrayList<>();
        for (String token : tokens) {
            optionsPerToken.add(candidatesFor(token, termOptions));
        }
        List<Beam> beams = new ArrayList<>();
        beams.add(new Beam(new ArrayList<>(), null, 0.0));
        for (List<WordOption> options : optionsPerToken) {
            List<Beam> next = new ArrayList<>();
            for (Beam beam : beams) {
                for (WordOption option : options) {
                    double transitionProb = model.interpolatedProbability(beam.prevWord, option.word);
                    double score = beam.score + Math.log(Math.max(transitionProb, 1e-12)) - option.penalty;
                    List<String> words = new ArrayList<>(beam.words);
                    words.add(option.word);
                    next.add(new Beam(words, option.word, score));
                }
            }
            next.sort(Comparator.comparingDouble((Beam b) -> b.score).reversed());
            beams = next.size() > beamWidth ? next.subList(0, beamWidth) : next;
        }
        List<PhraseCandidate> result = new ArrayList<>();
        for (Beam beam : beams) {
            String text = String.join(" ", beam.words);
            if (collate != null && !collate.test(text)) {
                continue;
            }
            result.add(new PhraseCandidate(beam.words, beam.score));
        }
        result.sort(Comparator.comparingDouble(PhraseCandidate::score).reversed());
        if (result.size() > size) {
            result = result.subList(0, size);
        }
        return result;
    }

    private List<WordOption> candidatesFor(String token, TermSuggestOptions termOptions) {
        List<WordOption> options = new ArrayList<>();
        long originalCount = model.unigramCount(token);
        options.add(new WordOption(token, 0.0));
        List<TermSuggestion> suggestions = termSuggester.suggest(token, termOptions);
        for (TermSuggestion s : suggestions) {
            if (s.text().equals(token)) {
                continue;
            }
            double penalty = realWordErrorPenalty * s.editDistance();
            options.add(new WordOption(s.text(), penalty));
            if (options.size() >= candidatesPerToken + 1) {
                break;
            }
        }
        return options;
    }

    private record WordOption(String word, double penalty) {
    }

    private record Beam(List<String> words, String prevWord, double score) {
    }
}
