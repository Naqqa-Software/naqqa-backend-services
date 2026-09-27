package com.naqqa.elasticsearch.common.settings;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.Properties;

public final class PropertiesSettingsLoader {

    private PropertiesSettingsLoader() {
    }

    public static Settings load(String content) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Settings.Builder builder = Settings.builder();
        for (String key : props.stringPropertyNames()) {
            builder.put(key, props.getProperty(key));
        }
        return builder.build();
    }
}
