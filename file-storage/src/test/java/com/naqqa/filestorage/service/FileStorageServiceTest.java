package com.naqqa.filestorage.service;

import com.google.cloud.ReadChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.naqqa.filestorage.config.FileStorageProperties;
import com.naqqa.filestorage.entities.FileEntity;
import com.naqqa.filestorage.repository.FileRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileStorageServiceTest {

    private final FileRepository repo = mock(FileRepository.class);
    private final Storage storage = mock(Storage.class);

    private static FileStorageProperties props(FileStorageProperties.Upload.Mode mode) {
        FileStorageProperties props = new FileStorageProperties();
        props.setBucketName("bucket");
        props.getUpload().setMode(mode);
        return props;
    }

    private FileStorageService service() {
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        return new FileStorageService(repo, storage, props(FileStorageProperties.Upload.Mode.RESUMABLE));
    }

    private BlobInfo storedBlob(MockMultipartFile file) throws Exception {
        byte[][] stored = new byte[1][];
        when(storage.createFrom(any(BlobInfo.class), any(InputStream.class))).thenAnswer(i -> {
            stored[0] = ((InputStream) i.getArgument(1)).readAllBytes();
            return mock(Blob.class);
        });
        service().uploadFile(file, "tasks", 7L);
        ArgumentCaptor<BlobInfo> info = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).createFrom(info.capture(), any(InputStream.class));
        assertEquals(file.getSize(), stored[0].length);
        return info.getValue();
    }

    private static void assertDownloadedAsBytes(BlobInfo blob) {
        assertEquals("application/octet-stream", blob.getContentType());
        assertEquals("attachment", blob.getContentDisposition());
    }

    @Test
    void activeContentIsStoredAsAttachment() throws Exception {
        BlobInfo blob = storedBlob(new MockMultipartFile("file", "x.html", "text/html",
                "<script>alert(1)</script>".getBytes()));
        assertDownloadedAsBytes(blob);
        assertTrue(blob.getName().startsWith("tasks/"), blob.getName());
    }

    @Test
    void svgKeepsItsTypeButIsDownloaded() throws Exception {
        BlobInfo blob = storedBlob(new MockMultipartFile("file", "a.svg", "image/svg+xml",
                "<svg onload=alert(1)></svg>".getBytes()));
        assertEquals("image/svg+xml", blob.getContentType());
        assertEquals("attachment", blob.getContentDisposition());
    }

    @Test
    void imagesStayInline() throws Exception {
        BlobInfo blob = storedBlob(new MockMultipartFile("file", "logo.png", "image/png; charset=x",
                new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0 }));
        assertNull(blob.getContentDisposition());
        assertEquals("image/png", blob.getContentType());
    }

    @Test
    void declaredTypeIsAStrictAllowlistCheckedAgainstTheBytes() throws Exception {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes();
        assertDownloadedAsBytes(storedBlob(new MockMultipartFile("file", "a.txt", "text/plain,text/html", html)));
        Mockito.reset(storage);
        assertDownloadedAsBytes(storedBlob(new MockMultipartFile("file", "a.txt", "text/plain", html)));
        Mockito.reset(storage);
        assertDownloadedAsBytes(storedBlob(new MockMultipartFile("file", "a.png", "image/png", html)));
        Mockito.reset(storage);
        assertDownloadedAsBytes(storedBlob(new MockMultipartFile("file", "a.x", "application/x-anything", html)));
        Mockito.reset(storage);
        assertDownloadedAsBytes(storedBlob(new MockMultipartFile("file", "a.x", null, html)));
        Mockito.reset(storage);
        BlobInfo text = storedBlob(new MockMultipartFile("file", "a.txt", "Text/Plain; charset=utf-8", "hello".getBytes()));
        assertEquals("text/plain", text.getContentType());
        assertNull(text.getContentDisposition());
    }

    @Test
    void streamUploadsAreSniffedWithoutLosingBytes() throws Exception {
        byte[] html = ("<svg onload=alert(1)>" + "x".repeat(2000)).getBytes();
        byte[][] stored = new byte[1][];
        when(storage.createFrom(any(BlobInfo.class), any(InputStream.class))).thenAnswer(i -> {
            stored[0] = ((InputStream) i.getArgument(1)).readAllBytes();
            return mock(Blob.class);
        });
        FileEntity saved = service().uploadFileInputStream(new ByteArrayInputStream(html), "a.png", "image/png", "d", 7L);
        ArgumentCaptor<BlobInfo> info = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).createFrom(info.capture(), any(InputStream.class));
        assertDownloadedAsBytes(info.getValue());
        assertEquals("application/octet-stream", saved.getContentType());
        assertEquals(html.length, stored[0].length);
    }

    @Test
    void signedPutUploadSignsTheContentType() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        com.google.auth.oauth2.ServiceAccountCredentials creds = com.google.auth.oauth2.ServiceAccountCredentials.newBuilder()
                .setClientEmail("signer@test.iam.gserviceaccount.com")
                .setPrivateKey(gen.generateKeyPair().getPrivate())
                .setProjectId("p")
                .build();
        Storage signing = com.google.cloud.storage.StorageOptions.newBuilder().setProjectId("p").setCredentials(creds)
                .build().getService();
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        String url = new FileStorageService(repo, signing, props(FileStorageProperties.Upload.Mode.SIGNED_PUT))
                .startResumableSession("docs", "x.pdf", "application/pdf", 7L).getUploadUrl();
        String signedHeaders = URLDecoder.decode(url.replaceAll(".*X-Goog-SignedHeaders=([^&]*).*", "$1"),
                StandardCharsets.UTF_8);
        assertTrue(signedHeaders.contains("content-type"), "Content-Type not signed: " + signedHeaders);
    }

    @Test
    void resumableUploadsAreCheckedAtInitiateAndAtCompletion() throws Exception {
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(storage.signUrl(any(BlobInfo.class), any(Long.class), any(), any(Storage.SignUrlOption[].class)))
                .thenReturn(new URL("https://storage.example/upload"));
        FileStorageService service = new FileStorageService(repo, storage, props(FileStorageProperties.Upload.Mode.SIGNED_PUT));
        ArgumentCaptor<FileEntity> saved = ArgumentCaptor.forClass(FileEntity.class);
        service.startResumableSession("docs", "a.txt", "text/plain,text/html", 7L);
        verify(repo).save(saved.capture());
        assertEquals("application/octet-stream", saved.getValue().getContentType());

        FileEntity pending = new FileEntity();
        pending.setId(9L);
        pending.setOwnerId(7L);
        pending.setFileName("docs/1_a.png");
        pending.setContentType("image/png");
        pending.setStatus("PENDING");
        when(repo.findById(9L)).thenReturn(Optional.of(pending));
        BlobId id = BlobId.of("bucket", "docs/1_a.png");
        Blob blob = mock(Blob.class);
        when(blob.exists()).thenReturn(true);
        when(blob.getSize()).thenReturn(40L);
        when(blob.getContentType()).thenReturn("image/png");
        when(storage.get(eq(id))).thenReturn(blob);
        ReadChannel channel = mock(ReadChannel.class);
        byte[] html = "<html><script>alert(1)</script></html>".getBytes();
        boolean[] served = new boolean[1];
        when(channel.read(any(ByteBuffer.class))).thenAnswer(i -> {
            if (served[0]) {
                return -1;
            }
            served[0] = true;
            ((ByteBuffer) i.getArgument(0)).put(html);
            return html.length;
        });
        when(storage.reader(eq(id))).thenReturn(channel);

        FileEntity done = service.finalizeUpload(9L, 1L, 7L, false);

        ArgumentCaptor<BlobInfo> update = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).update(update.capture());
        assertDownloadedAsBytes(update.getValue());
        assertEquals("application/octet-stream", done.getContentType());
        assertEquals(40L, done.getSize());
        assertEquals("COMPLETED", done.getStatus());
    }

    @Test
    void matchingStoredBytesKeepTheInlineType() throws Exception {
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        FileStorageService service = new FileStorageService(repo, storage, props(FileStorageProperties.Upload.Mode.SIGNED_PUT));
        FileEntity pending = new FileEntity();
        pending.setId(10L);
        pending.setOwnerId(7L);
        pending.setFileName("docs/1_b.pdf");
        pending.setContentType("application/pdf");
        when(repo.findById(10L)).thenReturn(Optional.of(pending));
        BlobId id = BlobId.of("bucket", "docs/1_b.pdf");
        Blob blob = mock(Blob.class);
        when(blob.exists()).thenReturn(true);
        when(blob.getSize()).thenReturn(5L);
        when(blob.getContentType()).thenReturn("application/pdf");
        when(storage.get(eq(id))).thenReturn(blob);
        ReadChannel channel = mock(ReadChannel.class);
        boolean[] served = new boolean[1];
        when(channel.read(any(ByteBuffer.class))).thenAnswer(i -> {
            if (served[0]) {
                return -1;
            }
            served[0] = true;
            ((ByteBuffer) i.getArgument(0)).put("%PDF-".getBytes());
            return 5;
        });
        when(storage.reader(eq(id))).thenReturn(channel);

        FileEntity done = service.finalizeUpload(10L, 5L, 7L, false);

        verify(storage, never()).update(any(BlobInfo.class));
        assertEquals("application/pdf", done.getContentType());
    }
}
