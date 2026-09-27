package com.naqqa.elasticsearch.analysis.phonetic;

import java.util.Locale;

public final class PhoneticEncoders {

    private PhoneticEncoders() {
    }

    public static PhoneticEncoder create(String name) {
        return create(name, null);
    }

    public static PhoneticEncoder create(String name, Integer maxCodeLen) {
        String key = name == null ? "metaphone" : name.toLowerCase(Locale.ROOT);
        switch (key) {
            case "metaphone":
                return new Metaphone();
            case "double_metaphone":
            case "doublemetaphone": {
                DoubleMetaphone doubleMetaphone = new DoubleMetaphone();
                if (maxCodeLen != null) {
                    doubleMetaphone.setMaxCodeLen(maxCodeLen);
                }
                return doubleMetaphone;
            }
            case "soundex":
                return new Soundex();
            case "refined_soundex":
            case "refinedsoundex":
                return new RefinedSoundex();
            case "caverphone1":
                return new Caverphone1();
            case "caverphone2":
            case "caverphone":
                return new Caverphone2();
            case "cologne":
                return new ColognePhonetic();
            case "koelnerphonetik":
                return new KoelnerPhonetik();
            case "haasephonetik":
                return new HaasePhonetik();
            case "nysiis":
                return new Nysiis();
            default:
                throw new IllegalArgumentException("unknown encoder [" + name + "] for phonetic token filter");
        }
    }
}
