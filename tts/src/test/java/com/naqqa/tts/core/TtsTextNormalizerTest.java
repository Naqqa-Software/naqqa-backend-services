package com.naqqa.tts.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TtsTextNormalizerTest {

    private final TtsTextNormalizer n = new TtsTextNormalizer();
    private final Map<String, String> ro = Map.of("OMY", "Omi");
    private final Map<String, String> ru = Map.of("OMY", "Оми");

    @Test
    void replacesBrandPronunciationInBothLanguages() {
        assertThat(n.normalize("Salut! Sunt OMY, asistentul tău.", "ro", ro, 400)).isEqualTo("Salut! Sunt Omi, asistentul tău.");
        assertThat(n.normalize("Привет! Я OMY, ваш помощник.", "ru", ru, 400)).isEqualTo("Привет! Я Оми, ваш помощник.");
    }

    @Test
    void doesNotTouchWordsContainingTheBrand() {
        assertThat(n.normalize("OMYX nu e marca", "ro", ro, 400)).isEqualTo("OMYX nu e marca");
    }

    @Test
    void speaksRomanianPrices() {
        assertThat(n.normalize("Laptele costă 12,99 lei.", "ro", ro, 400)).isEqualTo("Laptele costă 12 lei și 99 de bani.");
        assertThat(n.normalize("Doar 1 leu", "ro", ro, 400)).isEqualTo("Doar un leu");
        assertThat(n.normalize("Total 25 lei", "ro", ro, 400)).isEqualTo("Total 25 de lei");
        assertThat(n.normalize("Preț 3.05 MDL", "ro", ro, 400)).isEqualTo("Preț 3 lei și 5 bani");
    }

    @Test
    void speaksRussianPrices() {
        assertThat(n.normalize("Цена 12,99 лей", "ru", ru, 400)).isEqualTo("Цена 12 лей 99 бань");
        assertThat(n.normalize("Всего 22 лея", "ru", ru, 400)).isEqualTo("Всего 22 лея");
        assertThat(n.normalize("Всего 21 лей", "ru", ru, 400)).isEqualTo("Всего 21 лей");
    }

    @Test
    void speaksPercents() {
        assertThat(n.normalize("Reducere -30%", "ro", ro, 400)).isEqualTo("Reducere minus 30 la sută");
        assertThat(n.normalize("Скидка 25%", "ru", ru, 400)).isEqualTo("Скидка 25 процентов");
        assertThat(n.normalize("Скидка 3%", "ru", ru, 400)).isEqualTo("Скидка 3 процента");
    }

    @Test
    void removesLinksMarkdownAndEmoji() {
        String text = "**Promoții** la [Kaufland](https://omy.md/company/kaufland) 🎉\n- lapte\n- pâine\nVezi https://omy.md/promotions sau /map";
        assertThat(n.normalize(text, "ro", ro, 400)).isEqualTo("Promoții la Kaufland. lapte. pâine. Vezi sau");
    }

    @Test
    void expandsAbbreviations() {
        assertThat(n.normalize("Pachet de 2kg, 10 buc.", "ro", ro, 400)).isEqualTo("Pachet de 2 kilograme, 10 bucăți");
        assertThat(n.normalize("Упаковка 5 шт.", "ru", ru, 400)).isEqualTo("Упаковка 5 штук");
    }

    @Test
    void truncatesAtSentenceBoundary() {
        String text = "Prima propoziție este aici. A doua propoziție este mult mai lungă decât limita permisă pentru citire.";
        assertThat(n.normalize(text, "ro", ro, 60)).isEqualTo("Prima propoziție este aici.");
    }

    @Test
    void romanianCountsUseDeFromTwenty() {
        assertThat(TtsTextNormalizer.roCount(19, "leu", "lei")).isEqualTo("19 lei");
        assertThat(TtsTextNormalizer.roCount(20, "leu", "lei")).isEqualTo("20 de lei");
        assertThat(TtsTextNormalizer.roCount(101, "leu", "lei")).isEqualTo("101 lei");
        assertThat(TtsTextNormalizer.roCount(100, "leu", "lei")).isEqualTo("100 de lei");
    }
}
