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

    private FileEntity restoreDisplayName(FileEntity entity, String realFilename) {
        if (entity == null || realFilename == null || realFilename.equals(entity.getOriginalFileName())) {
            return entity;
        }
        entity.setOriginalFileName(realFilename);
        return fileRepository.save(entity);
    }


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
            }
            if (file != null && routePublic(file.getFileName())) {
                publicDelegate.deleteFile(fileId, requesterId, isAdmin);
                return;
            }
        }
        super.deleteFile(fileId, requesterId, isAdmin);
    }

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
