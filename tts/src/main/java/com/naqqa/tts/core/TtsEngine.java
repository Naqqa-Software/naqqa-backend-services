package com.naqqa.tts.core;

import com.naqqa.tts.config.TtsProperties;

import java.nio.file.Path;

public interface TtsEngine {

    boolean ready();

    void prepare();

    Path synthesize(String text, String lang, TtsProperties.Voice voice, Path output) throws TtsException;

    void shutdown();
}
