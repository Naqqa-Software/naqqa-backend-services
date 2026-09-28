package com.naqqa.elasticsearch.analysis.stem.snowball;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public final class RussianSnowballStemmerTest {

    private static final String[][] PAIRS = {
        {"книга", "книг"},
        {"книги", "книг"},
        {"книгу", "книг"},
        {"книгами", "книг"},
        {"дом", "дом"},
        {"дома", "дом"},
        {"домами", "дом"},
        {"домах", "дом"},
        {"делать", "дела"},
        {"делает", "дела"},
        {"делал", "дела"},
        {"делала", "дела"},
        {"делали", "дела"},
        {"большой", "больш"},
        {"большая", "больш"},
        {"большое", "больш"},
        {"большие", "больш"},
        {"красивый", "красив"},
        {"красивая", "красив"},
        {"читать", "чита"},
        {"читаю", "чита"},
        {"читаешь", "чита"},
        {"читает", "чита"},
        {"читали", "чита"},
        {"говорить", "говор"},
        {"говорю", "говор"},
        {"говоришь", "говор"},
        {"говорит", "говор"},
        {"работа", "работ"},
        {"работы", "работ"},
        {"работу", "работ"},
        {"работаю", "работа"},
        {"работает", "работа"},
        {"машина", "машин"},
        {"машины", "машин"},
        {"машину", "машин"}
    };

    @Test
    public void matchesOfficialSnowballVocabulary() {
        RussianSnowballStemmer stemmer = new RussianSnowballStemmer();
        for (String[] pair : PAIRS) {
            Assert.assertEquals(pair[1], stemmer.stem(pair[0]), "stem(" + pair[0] + ")");
        }
    }
}
