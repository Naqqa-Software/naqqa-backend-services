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
        assertThat(n.normalize("OMYX nu e marca", "ro", ro, 400)).isEqualToIgnoringCase("OMYX nu e marca");
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
    void speaksRangesUnitsAndDates() {
        assertThat(n.normalize("Reduceri 10-20%", "ro", ro, 400)).isEqualTo("Reduceri 10 până la 20 la sută");
        assertThat(n.normalize("Lapte 1L și brânză 500g", "ro", ro, 400)).isEqualTo("Lapte un litru și brânză 500 de grame");
        assertThat(n.normalize("Ulei 2,5 l", "ro", ro, 400)).isEqualTo("Ulei 2 virgulă 5 litri");
        assertThat(n.normalize("Roșii 25,90 lei/kg", "ro", ro, 400)).isEqualTo("Roșii 25 de lei și 90 de bani pe kilogram");
        assertThat(n.normalize("Valabil până la 03.10", "ro", ro, 400)).isEqualTo("Valabil până la 3 octombrie");
        assertThat(n.normalize("Молоко 1 л, сыр 300 г", "ru", ru, 400)).isEqualTo("Молоко 1 литр, сыр 300 граммов");
        assertThat(n.normalize("До 03.10.2026", "ru", ru, 400)).isEqualTo("До 3 октября 2026");
        assertThat(n.normalize("В 2026 г.", "ru", ru, 400)).isEqualTo("В 2026 г.");
    }

    @Test
    void readsCapitalsAsWordsAndLatinNamesInRussian() {
        assertThat(n.normalize("REDUCERI MARI la lapte", "ro", ro, 400)).isEqualTo("reduceri mari la lapte");
        assertThat(n.normalize("Скидки в Linella", "ru", Map.of("Linella", "Линелла"), 400)).isEqualTo("Скидки в Линелла");
        assertThat(n.normalize("Скидки в Kaufland", "ru", ru, 400)).isEqualTo("Скидки в Кауфланд");
        assertThat(n.normalize("Скидка 2,5%", "ru", ru, 400)).isEqualTo("Скидка 2 целых 5 десятых процента");
    }

    @Test
    void speaksDatesBeforePunctuation() {
        assertThat(n.normalize("Valabil până pe 16.10.2026.", "ro", ro, 400)).isEqualTo("Valabil până pe 16 octombrie 2026.");
        assertThat(n.normalize("Apare pe 01.11, iar promoțiile încep luni.", "ro", ro, 400)).isEqualTo("Apare pe 1 noiembrie, iar promoțiile încep luni.");
        assertThat(n.normalize("Действует по 16.10.2026.", "ru", ru, 400)).isEqualTo("Действует по 16 октября 2026.");
    }

    @Test
    void keepsSentenceBreakAfterUnits() {
        assertThat(n.normalize("Banane 19,90 lei/kg\n- Mere 14 lei/kg", "ro", ro, 400)).isEqualTo("Banane 19 lei și 90 de bani pe kilogram. Mere 14 lei pe kilogram");
        assertThat(n.normalize("Zahăr 2 kg. Făină 1 kg.", "ro", ro, 400)).isEqualTo("Zahăr 2 kilograme. Făină un kilogram");
    }

    @Test
    void readsPhoneNumbersDigitByDigit() {
        assertThat(n.normalize("Sună la 022 123 456.", "ro", ro, 400)).isEqualTo("Sună la 0 2 2, 1 2 3, 4 5 6.");
        assertThat(n.normalize("Звоните +373 69 123 456", "ru", ru, 400)).isEqualTo("Звоните плюс 3 7 3, 6 9, 1 2 3, 4 5 6");
        assertThat(n.normalize("Iphone 15 cu 1500 de lei", "ro", ro, 400)).isEqualTo("Iphone 15 cu 1500 de lei");
    }

    @Test
    void readsAmpersandAndRussianStreetCase() {
        assertThat(n.normalize("Șampon Head & Shoulders", "ro", Map.of("Head & Shoulders", "Hed end Șoldărs"), 400)).isEqualTo("Șampon Hed end Șoldărs");
        assertThat(n.normalize("Lapte & pâine", "ro", ro, 400)).isEqualTo("Lapte și pâine");
        assertThat(n.normalize("Магазин на ул. Измаил 88", "ru", ru, 400)).isEqualTo("Магазин на улице Измаил 88");
        assertThat(n.normalize("Вода 1,5 л", "ru", ru, 400)).isEqualTo("Вода одна целая 5 десятых литра");
    }

    @Test
    void romanianCountsUseDeFromTwenty() {
        assertThat(TtsTextNormalizer.roCount(19, "leu", "lei")).isEqualTo("19 lei");
        assertThat(TtsTextNormalizer.roCount(20, "leu", "lei")).isEqualTo("20 de lei");
        assertThat(TtsTextNormalizer.roCount(101, "leu", "lei")).isEqualTo("101 lei");
        assertThat(TtsTextNormalizer.roCount(100, "leu", "lei")).isEqualTo("100 de lei");
    }
}
