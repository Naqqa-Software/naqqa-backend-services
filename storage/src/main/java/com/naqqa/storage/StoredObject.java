package com.naqqa.storage;

public record StoredObject(String key, String bucket, Visibility visibility, String contentType, long size) {
}
