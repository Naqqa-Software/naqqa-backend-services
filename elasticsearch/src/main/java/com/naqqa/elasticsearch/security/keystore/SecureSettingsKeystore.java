package com.naqqa.elasticsearch.security.keystore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class SecureSettingsKeystore {

    private static final byte[] MAGIC = {'N', 'Q', 'K', 'S'};
    private static final int VERSION = 1;
    private static final int ITERATIONS = 200000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private enum SettingType {
        STRING, FILE
    }

    private record SettingValue(SettingType type, byte[] data) {
    }

    private final Map<String, SettingValue> entries;

    private SecureSettingsKeystore(Map<String, SettingValue> entries) {
        this.entries = entries;
    }

    public static SecureSettingsKeystore create() {
        return new SecureSettingsKeystore(new LinkedHashMap<>());
    }

    public void setString(String key, String value) {
        entries.put(key, new SettingValue(SettingType.STRING, value.getBytes(StandardCharsets.UTF_8)));
    }

    public void setFile(String key, byte[] content) {
        entries.put(key, new SettingValue(SettingType.FILE, content.clone()));
    }

    public void remove(String key) {
        entries.remove(key);
    }

    public boolean has(String key) {
        return entries.containsKey(key);
    }

    public List<String> listSettings() {
        return List.copyOf(entries.keySet());
    }

    public String getString(String key) {
        SettingValue value = requireEntry(key);
        if (value.type() != SettingType.STRING) {
            throw new SecureSettingsException("setting [" + key + "] is not a string setting");
        }
        return new String(value.data(), StandardCharsets.UTF_8);
    }

    public byte[] getFile(String key) {
        SettingValue value = requireEntry(key);
        if (value.type() != SettingType.FILE) {
            throw new SecureSettingsException("setting [" + key + "] is not a file setting");
        }
        return value.data().clone();
    }

    private SettingValue requireEntry(String key) {
        SettingValue value = entries.get(key);
        if (value == null) {
            throw new SecureSettingsException("setting [" + key + "] not found");
        }
        return value;
    }

    public void save(Path file, char[] password) throws IOException {
        byte[] salt = randomBytes(SALT_LENGTH);
        byte[] iv = randomBytes(IV_LENGTH);
        SecretKey key = deriveKey(password, salt, ITERATIONS);
        byte[] plaintext = serializeEntries();
        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            ciphertext = cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new SecureSettingsException("failed to encrypt keystore", e);
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.write(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(ITERATIONS);
            out.writeInt(salt.length);
            out.write(salt);
            out.writeInt(iv.length);
            out.write(iv);
            out.writeInt(ciphertext.length);
            out.write(ciphertext);
        }
    }

    public static SecureSettingsKeystore load(Path file, char[] password) throws IOException {
        byte[] magic = new byte[4];
        int version;
        int iterations;
        byte[] salt;
        byte[] iv;
        byte[] ciphertext;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new SecureSettingsException("not a valid secure settings keystore file");
            }
            version = in.readInt();
            if (version != VERSION) {
                throw new SecureSettingsException("unsupported keystore version " + version);
            }
            iterations = in.readInt();
            salt = new byte[in.readInt()];
            in.readFully(salt);
            iv = new byte[in.readInt()];
            in.readFully(iv);
            ciphertext = new byte[in.readInt()];
            in.readFully(ciphertext);
        }
        SecretKey key = deriveKey(password, salt, iterations);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return deserialize(plaintext);
        } catch (AEADBadTagException e) {
            throw new SecureSettingsException("incorrect keystore password");
        } catch (GeneralSecurityException e) {
            throw new SecureSettingsException("failed to decrypt keystore", e);
        }
    }

    private byte[] serializeEntries() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(entries.size());
            for (Map.Entry<String, SettingValue> entry : entries.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeByte(entry.getValue().type().ordinal());
                byte[] data = entry.getValue().data();
                out.writeInt(data.length);
                out.write(data);
            }
        }
        return buffer.toByteArray();
    }

    private static SecureSettingsKeystore deserialize(byte[] plaintext) throws IOException {
        Map<String, SettingValue> entries = new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plaintext))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String key = in.readUTF();
                SettingType type = SettingType.values()[in.readByte()];
                byte[] data = new byte[in.readInt()];
                in.readFully(data);
                entries.put(key, new SettingValue(type, data));
            }
        }
        return new SecureSettingsKeystore(entries);
    }

    private static SecretKey deriveKey(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (GeneralSecurityException e) {
            throw new SecureSettingsException("failed to derive keystore key", e);
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
