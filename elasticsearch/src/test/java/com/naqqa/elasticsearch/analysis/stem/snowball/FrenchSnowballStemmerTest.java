package com.naqqa.elasticsearch.analysis.stem.snowball;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public final class FrenchSnowballStemmerTest {

    private static final String[][] PAIRS = {
        {"chevaux", "cheval"},
        {"chat", "chat"},
        {"chats", "chat"},
        {"manger", "mang"},
        {"mangerait", "mang"},
        {"mangeait", "mang"},
        {"continuellement", "continuel"},
        {"continuelle", "continuel"},
        {"nation", "nation"},
        {"national", "national"},
        {"production", "product"},
        {"communication", "commun"},
        {"logique", "logiqu"},
        {"logiques", "logiqu"},
        {"confusion", "confus"},
        {"confusément", "confus"},
        {"adorer", "ador"},
        {"adorable", "ador"},
        {"animal", "animal"},
        {"animaux", "animal"},
        {"parler", "parl"},
        {"parlais", "parl"},
        {"parlait", "parl"},
        {"parlerons", "parl"},
        {"parlerez", "parl"},
        {"finir", "fin"},
        {"finissons", "fin"},
        {"finissant", "fin"},
        {"petit", "pet"},
        {"petite", "petit"},
        {"petits", "petit"},
        {"petites", "petit"},
        {"amoureuse", "amour"},
        {"amoureux", "amour"},
        {"calais", "calais"},
        {"malais", "malais"},
        {"mauvais", "mauvais"},
        {"bijoux", "bijou"},
        {"cailloux", "caillou"},
        {"choux", "chou"}
    };

    @Test
    public void matchesOfficialSnowballVocabulary() {
        FrenchSnowballStemmer stemmer = new FrenchSnowballStemmer();
        for (String[] pair : PAIRS) {
            Assert.assertEquals(pair[1], stemmer.stem(pair[0]), "stem(" + pair[0] + ")");
        }
    }
}
