package com.naqqa.filestorage.service;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.naqqa.filestorage.config.FileStorageProperties;
import com.naqqa.filestorage.entities.FileEntity;
import com.naqqa.filestorage.repository.FileRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AU-BE-29: uploads carry a client-chosen Content-Type and file name; stored objects must not render active content
 * inline from the bucket, and object keys must stay inside the caller's directory.
 */
class FileStorageServiceTest {

    private final FileRepository repo = mock(FileRepository.class);
    private final Storage storage = mock(Storage.class);

    private FileStorageService service() {
        FileStorageProperties props = new FileStorageProperties();
        props.setBucketName("bucket");
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        return new FileStorageService(repo, storage, props);
    }

    @Test
    void activeContentIsStoredAsAttachment() {
        MockMultipartFile html = new MockMultipartFile("file", "../../x<y>.html", "text/html", "<script>alert(1)</script>".getBytes());
        service().uploadFile(html, "tasks/../../avatars", 7L);

        ArgumentCaptor<BlobInfo> info = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).create(info.capture(), any(byte[].class));
        BlobInfo blob = info.getValue();
        assertTrue(blob.getContentDisposition() != null && blob.getContentDisposition().startsWith("attachment"),
                "HTML must not render inline: " + blob.getContentDisposition());
        String key = blob.getName();
        assertFalse(key.contains(".."), key);
        assertTrue(key.startsWith("tasks/avatars/"), key);
        assertFalse(key.substring("tasks/avatars/".length()).contains("/"), key);
    }

    @Test
    void imagesStayInline() {
        MockMultipartFile png = new MockMultipartFile("file", "logo.png", "image/png", new byte[] { 1, 2, 3 });
        service().uploadFile(png, "designs", 7L);
        ArgumentCaptor<BlobInfo> info = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).create(info.capture(), any(byte[].class));
        assertNull(info.getValue().getContentDisposition());
        assertEquals("image/png", info.getValue().getContentType());
    }

    @Test
    void finalizedSizeComesFromTheBucketNotTheClient() {
        FileEntity pending = new FileEntity();
        pending.setId(5L);
        pending.setOwnerId(7L);
        pending.setFileName("courses/videos/1_a.mp4");
        pending.setStatus("PENDING");
        when(repo.findById(5L)).thenReturn(Optional.of(pending));
        Blob blob = mock(Blob.class);
        when(blob.getSize()).thenReturn(1234L);
        when(storage.get(eq(com.google.cloud.storage.BlobId.of("bucket", "courses/videos/1_a.mp4")))).thenReturn(blob);

        FileEntity done = service().finalizeUpload(5L, 1L, 7L, false);

        assertEquals(1234L, done.getSize());
        assertEquals("COMPLETED", done.getStatus());
    }

    /** AU-BE-48: the signed resumable PUT must sign the Content-Type, or the client stores any type it likes. */
    @Test
    void resumableUploadSignsTheContentType() throws Exception {
        java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        com.google.auth.oauth2.ServiceAccountCredentials creds = com.google.auth.oauth2.ServiceAccountCredentials.newBuilder()
                .setClientEmail("signer@test.iam.gserviceaccount.com")
                .setPrivateKey(gen.generateKeyPair().getPrivate())
                .setProjectId("p")
                .build();
        Storage signing = com.google.cloud.storage.StorageOptions.newBuilder().setProjectId("p").setCredentials(creds)
                .build().getService();
        FileStorageProperties props = new FileStorageProperties();
        props.setBucketName("bucket");
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        String url = new FileStorageService(repo, signing, props)
                .startResumableSession("docs", "x.pdf", "application/pdf", 7L).getUploadUrl();
        String signedHeaders = java.net.URLDecoder.decode(url.replaceAll(".*X-Goog-SignedHeaders=([^&]*).*", "$1"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(signedHeaders.contains("content-type"), "Content-Type not signed: " + signedHeaders);
    }
}
