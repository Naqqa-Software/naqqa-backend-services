package com.naqqa.storage;

/**
 * Result of an upload: the stored object's key (unique within the backend), which bucket/visibility
 * tier it landed in, its content type and size in bytes. The {@code key} is what callers persist and
 * later pass to {@link NaqqaStorage#publicUrl(String)} / {@link NaqqaStorage#signedUrl} /
 * {@link NaqqaStorage#download(String)} / {@link NaqqaStorage#delete(String)}.
 */
public record StoredObject(String key, String bucket, Visibility visibility, String contentType, long size) {
}
