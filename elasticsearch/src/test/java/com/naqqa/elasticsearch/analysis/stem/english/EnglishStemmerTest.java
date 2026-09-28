package com.naqqa.elasticsearch.analysis.stem.english;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public final class EnglishStemmerTest {

    private static final String[][] PAIRS = {
        {"caresses", "caress"},
        {"ponies", "poni"},
        {"ties", "tie"},
        {"caress", "caress"},
        {"cats", "cat"},
        {"feed", "feed"},
        {"agreed", "agre"},
        {"plastered", "plaster"},
        {"bled", "bled"},
        {"sing", "sing"},
        {"troubled", "troubl"},
        {"sized", "size"},
        {"hopping", "hop"},
        {"tanned", "tan"},
        {"falling", "fall"},
        {"hissing", "hiss"},
        {"failing", "fail"},
        {"filing", "file"},
        {"happy", "happi"},
        {"sky", "sky"},
        {"conditional", "condit"},
        {"rational", "ration"},
        {"operator", "oper"},
        {"hopefulness", "hope"},
        {"callousness", "callous"},
        {"electrical", "electr"},
        {"hopeful", "hope"},
        {"goodness", "good"},
        {"revival", "reviv"},
        {"allowance", "allow"},
        {"inference", "infer"},
        {"defensible", "defens"},
        {"adjustment", "adjust"},
        {"dependent", "depend"},
        {"adoption", "adopt"},
        {"communism", "communism"},
        {"poisonous", "poison"},
        {"cylindrical", "cylindr"},
        {"pardonable", "pardon"},
        {"comparable", "compar"},
        {"studying", "studi"},
        {"relate", "relat"},
        {"organization", "organiz"},
        {"organizations", "organiz"},
        {"universities", "universiti"},
        {"university", "universiti"},
        {"emergency", "emergenc"},
        {"emergencies", "emergenc"},
        {"international", "internat"},
        {"lateral", "lateral"},
        {"internal", "internal"},
        {"interfered", "interfer"},
        {"paste", "paste"},
        {"pasted", "paste"},
        {"dying", "die"},
        {"vying", "vie"},
        {"evening", "evening"},
        {"evenings", "evening"},
        {"geologist", "geolog"},
        {"psychologist", "psycholog"}
    };

    @Test
    public void matchesOfficialPorter2Vocabulary() {
        EnglishStemmer stemmer = new EnglishStemmer();
        for (String[] pair : PAIRS) {
            Assert.assertEquals(pair[1], stemmer.stem(pair[0]), "stem(" + pair[0] + ")");
        }
    }
}
