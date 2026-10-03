package com.naqqa.storage;

import java.time.Duration;

/**
 * Object storage over two Google Cloud Storage buckets — a public one (images; served by public URL)
 * and a private one (invoices/per-user files; served only by V4 signed URL). The backing library holds
 * no database: callers persist the returned {@link StoredObject#key()} in their own store.
 *
 * <p>Keys are unique across both tiers, so {@link #download(String)}, {@link #delete(String)} and
 * {@link #visibilityOf(String)} resolve a key without the caller tracking which bucket it is in.
 */
public interface NaqqaStorage {

    /** Upload with an auto-generated key (UUID). */
    StoredObject upload(byte[] data, String contentType, Visibility visibility);

    /** Upload under an explicit key (e.g. a derived variant name, or a pre-existing id during migration). */
    StoredObject upload(String key, byte[] data, String contentType, Visibility visibility);

    /** Read the object's bytes, searching the public tier then the private tier. Null if absent. */
    byte[] download(String key);

    /** Whether an object with this key exists in either tier. */
    boolean exists(String key);

    /** Delete the object from whichever tier holds it (no-op if absent). */
    void delete(String key);

    /** The stable public URL for a public object (no existence check). */
    String publicUrl(String key);

    /** A short-lived V4 signed read URL for a private object. */
    String signedUrl(String key, Duration ttl);

    /** Which tier currently holds the key, or null if it exists in neither. */
    Visibility visibilityOf(String key);
}
