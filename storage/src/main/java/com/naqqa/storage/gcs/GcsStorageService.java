package com.naqqa.storage.gcs;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.naqqa.storage.NaqqaStorage;
import com.naqqa.storage.StoredObject;
import com.naqqa.storage.Visibility;
import com.naqqa.storage.config.StorageProperties;

import java.net.URL;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@link NaqqaStorage} over two GCS buckets. Public objects are written with a {@code publicRead} ACL
 * and an immutable one-year cache; private objects are written with no public ACL (the private bucket
 * also has public-access-prevention enforced) and read back only via a V4 signed URL — which works
 * offline because the {@link Storage} client is built from a service-account key with a private key.
 */
public class GcsStorageService implements NaqqaStorage {

    private final Storage storage;
    private final String publicBucket;
    private final String privateBucket;
    private final String publicBaseUrl;
    private final int defaultTtlSeconds;

    public GcsStorageService(Storage storage, StorageProperties.Gcs gcs) {
        this.storage = storage;
        this.publicBucket = gcs.getPublicBucket();
        this.privateBucket = gcs.getPrivateBucket();
        String base = gcs.getPublicBaseUrl();
        this.publicBaseUrl = (base == null || base.isBlank()) ? "https://storage.googleapis.com" : stripTrailingSlash(base);
        this.defaultTtlSeconds = gcs.getSignedUrlTtlSeconds() > 0 ? gcs.getSignedUrlTtlSeconds() : 300;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private String bucketFor(Visibility v) {
        return v == Visibility.PUBLIC ? publicBucket : privateBucket;
    }

    @Override
    public StoredObject upload(byte[] data, String contentType, Visibility visibility) {
        return upload(UUID.randomUUID().toString(), data, contentType, visibility);
    }

    @Override
    public StoredObject upload(String key, byte[] data, String contentType, Visibility visibility) {
        String bucket = bucketFor(visibility);
        BlobInfo.Builder builder = BlobInfo.newBuilder(BlobId.of(bucket, key));
        if (contentType != null && !contentType.isBlank()) {
            builder.setContentType(contentType);
        }
        if (visibility == Visibility.PUBLIC) {
            builder.setCacheControl("public, max-age=31536000, immutable");
            storage.create(builder.build(), data, Storage.BlobTargetOption.predefinedAcl(Storage.PredefinedAcl.PUBLIC_READ));
        } else {
            builder.setCacheControl("private, max-age=0, no-store");
            storage.create(builder.build(), data);
        }
        return new StoredObject(key, bucket, visibility, contentType, data == null ? 0 : data.length);
    }

    @Override
    public byte[] download(String key) {
        byte[] pub = readOrNull(publicBucket, key);
        return pub != null ? pub : readOrNull(privateBucket, key);
    }

    private byte[] readOrNull(String bucket, String key) {
        try {
            return storage.readAllBytes(BlobId.of(bucket, key));
        } catch (StorageException e) {
            if (e.getCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    @Override
    public boolean exists(String key) {
        return blobExists(publicBucket, key) || blobExists(privateBucket, key);
    }

    private boolean blobExists(String bucket, String key) {
        Blob b = storage.get(BlobId.of(bucket, key));
        return b != null;
    }

    @Override
    public void delete(String key) {
        boolean deleted = storage.delete(BlobId.of(publicBucket, key));
        if (!deleted) {
            storage.delete(BlobId.of(privateBucket, key));
        }
    }

    @Override
    public String publicUrl(String key) {
        return publicBaseUrl + "/" + publicBucket + "/" + key;
    }

    @Override
    public String signedUrl(String key, Duration ttl) {
        long seconds = (ttl == null || ttl.getSeconds() <= 0) ? defaultTtlSeconds : ttl.getSeconds();
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(privateBucket, key)).build();
        URL url = storage.signUrl(blobInfo, seconds, TimeUnit.SECONDS, Storage.SignUrlOption.withV4Signature());
        return url.toString();
    }

    @Override
    public Visibility visibilityOf(String key) {
        if (blobExists(publicBucket, key)) {
            return Visibility.PUBLIC;
        }
        if (blobExists(privateBucket, key)) {
            return Visibility.PRIVATE;
        }
        return null;
    }
}
