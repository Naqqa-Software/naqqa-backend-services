package com.naqqa.filestorage;

import com.naqqa.filestorage.config.FileStorageProperties;
import com.naqqa.filestorage.entities.FileEntity;
import com.naqqa.filestorage.enums.FileAccessEnum;
import com.naqqa.filestorage.service.FileStorageService;
import com.naqqa.filestorage.service.RoutingFileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import static org.junit.jupiter.api.Assertions.*;

class RoutingFileStorageServiceTest {

    private RoutingFileStorageService service(String publicBucket, String baseUrl) {
        FileStorageProperties props = new FileStorageProperties();
        props.setBucketName("private-bucket");
        props.setPublicBucketName(publicBucket);
        props.setPublicBaseUrl(baseUrl);
        // Storage and repository are untouched on the public path — no signing, no I/O.
        return new RoutingFileStorageService(null, null, props);
    }

    private FileEntity file(String key) {
        FileEntity f = new FileEntity();
        f.setFileName(key);
        return f;
    }

    /**
     * The parent {@link FileStorageService} is annotated {@code @Service}, so any application
     * scanning this package registers it. Without these annotations here, auto-configuration backs
     * off through {@code @ConditionalOnMissingBean}, the plain service wins, and every public object
     * is signed against the private bucket — a 404 on every image, with nothing in the logs.
     */
    @Test
    void isRegisteredByScanningAndWinsOverThePlainService() {
        assertTrue(RoutingFileStorageService.class.isAnnotationPresent(Service.class),
                "must be component-scannable, or scanning registers only the plain parent");
        assertTrue(RoutingFileStorageService.class.isAnnotationPresent(Primary.class),
                "must win injection over the scanned parent bean");
        assertTrue(FileStorageService.class.isAnnotationPresent(Service.class),
                "if the parent stops being scanned this test's premise changes — re-check the wiring");
    }

    @Test
    void publicKeysGetAnUnsignedUrlFromThePublicBucket() {
        String url = service("public-bucket", "").getFileUrl(file("public/avatars/a.jpg"), FileAccessEnum.PUBLIC_READ);
        assertEquals("https://storage.googleapis.com/public-bucket/public/avatars/a.jpg", url);
    }

    @Test
    void publicKeysGoThroughTheCdnOriginWhenConfigured() {
        String url = service("public-bucket", "https://cdn.example.com/")
                .getFileUrl(file("public/avatars/a.jpg"), FileAccessEnum.PUBLIC_READ);
        assertEquals("https://cdn.example.com/public/avatars/a.jpg", url);
    }

    @Test
    void pathSegmentsAreEncodedButSlashesArePreserved() {
        String url = service("public-bucket", "").getFileUrl(file("public/a b/ç.jpg"), FileAccessEnum.PUBLIC_READ);
        assertEquals("https://storage.googleapis.com/public-bucket/public/a%20b/%C3%A7.jpg", url);
    }

    @Test
    void aNullFileIsNotAnError() {
        assertNull(service("public-bucket", "").getFileUrl(null, FileAccessEnum.PUBLIC_READ));
    }

    @Test
    void withNoPublicBucketNothingIsRoutedAway() {
        // Blank public bucket must degrade to plain behaviour rather than half-routing.
        RoutingFileStorageService s = service("", "");
        // A private key would be signed (needs GCS); assert instead that the public path is not taken
        // by checking it does not return an unsigned public URL.
        assertThrows(Exception.class,
                () -> s.getFileUrl(file("public/avatars/a.jpg"), FileAccessEnum.PUBLIC_READ),
                "with no public bucket this must fall through to signing, not emit a public URL");
    }
}
