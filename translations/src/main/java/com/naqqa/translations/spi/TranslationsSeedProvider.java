package com.naqqa.translations.spi;

import java.util.Map;

public interface TranslationsSeedProvider {

    Map<String, Object> seed();
}
