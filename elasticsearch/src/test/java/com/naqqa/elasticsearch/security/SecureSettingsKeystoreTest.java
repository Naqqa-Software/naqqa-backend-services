package com.naqqa.elasticsearch.security;

import com.naqqa.elasticsearch.security.keystore.SecureSettingsException;
import com.naqqa.elasticsearch.security.keystore.SecureSettingsKeystore;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class SecureSettingsKeystoreTest {

    @Test
    public void roundTripsStringAndFileSettings() throws IOException {
        Path file = Files.createTempFile("naqqa-keystore", ".bin");
        try {
            SecureSettingsKeystore keystore = SecureSettingsKeystore.create();
            keystore.setString("db.password", "hunter2");
            keystore.setFile("tls.key", new byte[]{1, 2, 3, 4, 5});
            keystore.save(file, "correct password".toCharArray());

            SecureSettingsKeystore reloaded = SecureSettingsKeystore.load(file, "correct password".toCharArray());
            assertEquals("hunter2", reloaded.getString("db.password"));
            assertTrue(java.util.Arrays.equals(new byte[]{1, 2, 3, 4, 5}, reloaded.getFile("tls.key")));
            assertEquals(2, reloaded.listSettings().size());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void wrongPasswordThrowsClearError() throws IOException {
        Path file = Files.createTempFile("naqqa-keystore", ".bin");
        try {
            SecureSettingsKeystore keystore = SecureSettingsKeystore.create();
            keystore.setString("key", "value");
            keystore.save(file, "correct password".toCharArray());

            SecureSettingsException e = assertThrows(SecureSettingsException.class,
                    () -> SecureSettingsKeystore.load(file, "wrong password".toCharArray()));
            assertTrue(e.getMessage().toLowerCase().contains("password"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void removeSettingDropsIt() {
        SecureSettingsKeystore keystore = SecureSettingsKeystore.create();
        keystore.setString("a", "1");
        keystore.remove("a");
        assertTrue(keystore.listSettings().isEmpty());
    }
}
