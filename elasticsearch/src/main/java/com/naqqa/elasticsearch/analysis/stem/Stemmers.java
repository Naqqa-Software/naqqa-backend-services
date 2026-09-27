package com.naqqa.elasticsearch.analysis.stem;

import com.naqqa.elasticsearch.analysis.stem.english.EnglishMinimalStemmer;
import com.naqqa.elasticsearch.analysis.stem.english.EnglishPossessiveStemmer;
import com.naqqa.elasticsearch.analysis.stem.english.EnglishStemmer;
import com.naqqa.elasticsearch.analysis.stem.english.KStemmer;
import com.naqqa.elasticsearch.analysis.stem.english.PorterStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.CharArrayStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.DutchLightStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.FinnishLightStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.FrenchLightStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.FrenchMinimalStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.GalicianMinimalStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.GermanLightStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.GermanMinimalStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.ItalianLightStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.NorwegianLightStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.NorwegianMinimalStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.PortugueseLightStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.PortugueseMinimalStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.RussianLightStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.SpanishLightStemmer;
import com.naqqa.elasticsearch.analysis.stem.light.SwedishLightStemmer;
import com.naqqa.elasticsearch.analysis.stem.snowball.DanishSnowballStemmer;
import com.naqqa.elasticsearch.analysis.stem.snowball.FinnishSnowballStemmer;
import com.naqqa.elasticsearch.analysis.stem.snowball.FrenchSnowballStemmer;
import com.naqqa.elasticsearch.analysis.stem.snowball.ItalianSnowballStemmer;
import com.naqqa.elasticsearch.analysis.stem.snowball.NorwegianSnowballStemmer;
import com.naqqa.elasticsearch.analysis.stem.snowball.RussianSnowballStemmer;
import com.naqqa.elasticsearch.analysis.stem.snowball.SpanishSnowballStemmer;
import com.naqqa.elasticsearch.analysis.stem.snowball.SwedishSnowballStemmer;

import java.util.Locale;

public final class Stemmers {

    private Stemmers() {
    }

    public static Stemmer create(String language) {
        String key = language == null ? "english" : language.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "english" -> new EnglishStemmer();
            case "porter", "porter1" -> new PorterStemmer();
            case "porter2" -> new EnglishStemmer();
            case "light_english", "lightenglish" -> new EnglishMinimalStemmer();
            case "minimal_english" -> new EnglishMinimalStemmer();
            case "possessive_english" -> new EnglishPossessiveStemmer();
            case "kstem" -> new KStemmer();
            case "french" -> new FrenchSnowballStemmer();
            case "light_french" -> wrap(new FrenchLightStemmer());
            case "minimal_french" -> wrap(new FrenchMinimalStemmer());
            case "german" -> wrap(new GermanLightStemmer());
            case "german2" -> wrap(new GermanLightStemmer());
            case "light_german" -> wrap(new GermanLightStemmer());
            case "minimal_german" -> wrap(new GermanMinimalStemmer());
            case "spanish" -> new SpanishSnowballStemmer();
            case "light_spanish" -> wrap(new SpanishLightStemmer());
            case "italian" -> new ItalianSnowballStemmer();
            case "light_italian" -> wrap(new ItalianLightStemmer());
            case "portuguese" -> wrap(new PortugueseLightStemmer());
            case "light_portuguese" -> wrap(new PortugueseLightStemmer());
            case "minimal_portuguese" -> wrap(new PortugueseMinimalStemmer());
            case "russian" -> new RussianSnowballStemmer();
            case "light_russian" -> wrap(new RussianLightStemmer());
            case "swedish" -> new SwedishSnowballStemmer();
            case "light_swedish" -> wrap(new SwedishLightStemmer());
            case "finnish" -> new FinnishSnowballStemmer();
            case "light_finnish" -> wrap(new FinnishLightStemmer());
            case "dutch" -> wrap(new DutchLightStemmer());
            case "danish" -> new DanishSnowballStemmer();
            case "norwegian" -> new NorwegianSnowballStemmer();
            case "light_norwegian" -> wrap(new NorwegianLightStemmer());
            case "minimal_norwegian" -> wrap(new NorwegianMinimalStemmer());
            case "galician" -> wrap(new GalicianMinimalStemmer());
            case "minimal_galician" -> wrap(new GalicianMinimalStemmer());
            default -> throw new IllegalArgumentException("Unknown stemmer language [" + language + "]");
        };
    }

    private static Stemmer wrap(CharArrayStemmer s) {
        return s;
    }
}
