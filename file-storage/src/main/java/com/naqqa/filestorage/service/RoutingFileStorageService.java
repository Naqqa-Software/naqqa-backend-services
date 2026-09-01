package com.naqqa.filestorage.service;

import com.google.cloud.storage.Storage;
import com.naqqa.filestorage.config.FileStorageProperties;
import com.naqqa.filestorage.entities.FileEntity;
import com.naqqa.filestorage.enums.FileAccessEnum;
import com.naqqa.filestorage.model.InitiateUploadResponse;
import com.naqqa.filestorage.repository.FileRepository;
import com.naqqa.filestorage.support.StorageKeys;
import com.naqqa.filestorage.support.UniquelyNamedMultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * A {@link FileStorageService} that adds two optional behaviours: a public/private bucket split, and
 * collision-proof object names.
 *
 * <p><b>Bucket split.</b> Keys beginning with {@code gcs.lib.public-prefix} live in
 * {@code gcs.lib.public-bucket-name} and are served as plain unsigned URLs — CDN-friendly, with no
 * per-request signBlob round-trip. Everything else stays in the private bucket and is signed (or
 * proxied by the application). The split is decided purely by the directory the caller uploads to.
 *
 * <p><b>How.</b> This subclasses {@code FileStorageService}, whose {@code super(...)} is the private
 * bucket, and holds a second real instance configured for the public bucket. Public-key operations
 * are delegated to it, reusing the library's own upload / resumable / delete logic against the other
 * bucket rather than reimplementing any of it. Only URL generation is overridden, to skip signing.
 *
 * <p><b>Degrades to nothing.</b> With {@code public-bucket-name} blank and
 * {@code unique-object-names} false, every method here is a pass-through and behaviour is identical
 * to the plain service — so it is safe to register unconditionally.
 *
 * <p><b>Why {@code @Service @Primary} and not just an auto-configured bean.</b> The parent
 * {@link FileStorageService} is itself annotated {@code @Service}, so any application that
 * component-scans {@code com.naqqa.filestorage} registers it by scanning. Auto-configuration then
 * sees a {@code FileStorageService} already present, backs off through
 * {@code @ConditionalOnMissingBean}, and this class is never created — silently reverting the
 * application to single-bucket behaviour, where every {@code public/} key is signed against the
 * private bucket and 404s. Registering by scanning too, and winning on {@code @Primary}, is what
 * keeps both wiring styles working.
 */
@Service
@Primary
public class RoutingFileStorageService extends FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(RoutingFileStorageService.class);

    private final FileStorageService publicDelegate;
    private final FileRepository fileRepository;
    private final FileStorageProperties props;
    private final boolean publicEnabled;
    private final String publicPrefix;
    private final String publicBaseUrl;
    private final boolean uniqueNames;

    public RoutingFileStorageService(FileRepository fileRepository, Storage storage,
                                     FileStorageProperties props) {
        super(fileRepository, storage, props);
        this.fileRepository = fileRepository;
        this.props = props;
        this.publicPrefix = props.getPublicPrefix();
        this.uniqueNames = props.isUniqueObjectNames();

        String publicBucket = props.getPublicBucketName();
        this.publicEnabled = publicBucket != null && !publicBucket.isBlank();
        this.publicBaseUrl = props.getPublicBaseUrl() == null
                ? "" : props.getPublicBaseUrl().replaceAll("/+$", "");

        if (this.publicEnabled) {
            // Same client, credentials, project and file table; only the bucket differs. The Storage
            // client is not bucket-bound — the bucket is a per-operation argument — so a second
            // service with public props writes and deletes against the other bucket using lib code.
            FileStorageProperties pub = new FileStorageProperties();
            pub.setConfigFile(props.getConfigFile());
            pub.setProjectId(props.getProjectId());
            pub.setBucketName(publicBucket);
            this.publicDelegate = new FileStorageService(fileRepository, storage, pub);
            log.info("Dual-bucket storage enabled: public='{}', private='{}'",
                    publicBucket, props.getBucketName());
        } else {
            this.publicDelegate = null;
            log.info("Dual-bucket storage disabled — all objects use bucket '{}'", props.getBucketName());
        }
    }

    /**
     * True when the key belongs in the public bucket AND a public bucket is configured.
     *
     * <p>Sanitises first, because the stored key is built from the sanitised directory. Routing on
     * the raw value let the two disagree — {@code " public/x"} routes as private but is stored under
     * a {@code public/} key, landing world-readable content in the private bucket, where it is then
     * served as an unsigned URL that 403s.
     */
    private boolean routePublic(String key) {
        if (!publicEnabled || key == null) {
            return false;
        }
        String normalised;
        try {
            normalised = sanitizeDirectory(key);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return normalised.startsWith(publicPrefix);
    }

    private String maybeUniquify(String fileName) {
        return uniqueNames ? StorageKeys.uniquify(fileName) : fileName;
    }

    /**
     * Puts the caller's real filename back on the row after the object was stored under a uniquified
     * one. Only the object key carries the token; what the UI shows is unchanged.
     */
    private FileEntity restoreDisplayName(FileEntity entity, String realFilename) {
        if (entity == null || realFilename == null || realFilename.equals(entity.getOriginalFileName())) {
            return entity;
        }
        entity.setOriginalFileName(realFilename);
        return fileRepository.save(entity);
    }

    // ── Writes: route by the target directory ───────────────────────────────────

    @Override
    public FileEntity uploadFile(MultipartFile file, String directory, Long ownerId) {
        if (!uniqueNames && !publicEnabled) {
            return super.uploadFile(file, directory, ownerId);
        }
        MultipartFile toStore = uniqueNames ? new UniquelyNamedMultipartFile(file) : file;
        FileEntity saved = routePublic(directory)
                ? publicDelegate.uploadFile(toStore, directory, ownerId)
                : super.uploadFile(toStore, directory, ownerId);
        return uniqueNames
                ? restoreDisplayName(saved, ((UniquelyNamedMultipartFile) toStore).realFilename())
                : saved;
    }

    @Override
    public FileEntity uploadFileInputStream(InputStream inputStream, String fileName, String contentType,
                                            String directory, Long ownerId) {
        String stored = maybeUniquify(fileName);
        FileEntity saved = routePublic(directory)
                ? publicDelegate.uploadFileInputStream(inputStream, stored, contentType, directory, ownerId)
                : super.uploadFileInputStream(inputStream, stored, contentType, directory, ownerId);
        return restoreDisplayName(saved, fileName);
    }

    /**
     * Large media takes this path: the client PUTs straight to the bucket, so the object key is fixed
     * here, before a single byte is sent.
     *
     * <p><b>Parameter order matters.</b> It is {@code (directory, fileName, contentType, ownerId)}.
     * All three leading parameters are {@code String}, so an override that names them in a different
     * order still compiles and still satisfies {@code @Override} — while routing on the wrong value.
     * That exact mistake sent public resumable uploads to the private bucket, where they were then
     * served as unsigned public URLs that 403.
     */
    @Override
    public InitiateUploadResponse startResumableSession(String directory, String fileName,
                                                        String contentType, Long ownerId) {
        String stored = maybeUniquify(fileName);

        InitiateUploadResponse response = routePublic(directory)
                ? publicDelegate.startResumableSession(directory, stored, contentType, ownerId)
                : super.startResumableSession(directory, stored, contentType, ownerId);

        fileRepository.findById(response.getFileId())
                .ifPresent(entity -> restoreDisplayName(entity, fileName));

        return response;
    }

    // ── Reads: plain URL for public keys, signed for private ────────────────────

    @Override
    public String getFileUrl(FileEntity file, FileAccessEnum access) {
        if (file != null && routePublic(file.getFileName())) {
            return plainPublicUrl(file.getFileName());
        }
        return super.getFileUrl(file, access);
    }

    @Override
    public boolean existsInStorage(FileEntity file) {
        if (file != null && routePublic(file.getFileName())) {
            return publicDelegate.existsInStorage(file);
        }
        return super.existsInStorage(file);
    }

    @Override
    public void deleteFile(Long fileId, Long requesterId, boolean isAdmin) {
        if (publicEnabled) {
            FileEntity file = null;
            try {
                file = getFileById(fileId);
            } catch (Exception ignored) {
                // Not found / already gone — fall through; the private path handles it.
            }
            if (file != null && routePublic(file.getFileName())) {
                publicDelegate.deleteFile(fileId, requesterId, isAdmin);
                return;
            }
        }
        super.deleteFile(fileId, requesterId, isAdmin);
    }

    /**
     * Unsigned URL for a world-readable object, encoding each path segment while preserving the
     * {@code /} separators of the key.
     *
     * <p>With {@code public-base-url} set the object is served through that origin (a CDN or load
     * balancer) instead of straight from GCS: cached at the edge rather than fetched from a single
     * region by every viewer. The key is appended unchanged, so a path rule matching the public
     * prefix maps through with no rewriting.
     */
    private String plainPublicUrl(String key) {
        StringBuilder path = new StringBuilder();
        for (String segment : key.split("/", -1)) {
            if (path.length() > 0) path.append('/');
            path.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        if (!publicBaseUrl.isEmpty()) {
            return publicBaseUrl + "/" + path;
        }
        return "https://storage.googleapis.com/" + props.getPublicBucketName() + "/" + path;
    }
}
