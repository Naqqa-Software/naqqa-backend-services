package com.naqqa.storage;

import java.time.Duration;

public interface NaqqaStorage {

    StoredObject upload(byte[] data, String contentType, Visibility visibility);

    StoredObject upload(String key, byte[] data, String contentType, Visibility visibility);

    byte[] download(String key);

    boolean exists(String key);

    void delete(String key);

    String publicUrl(String key);

    String signedUrl(String key, Duration ttl);

    Visibility visibilityOf(String key);
}
