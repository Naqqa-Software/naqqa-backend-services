package com.naqqa.filestorage.support;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * A {@link MultipartFile} that reports a collision-proof filename.
 *
 * <p>{@code uploadFile} reads {@code getOriginalFilename()} to build the object key, and there is no
 * other seam to influence it without re-implementing the upload. Wrapping the file is the least
 * invasive way to make that key unique; every other call is delegated untouched, so the bytes, size
 * and content type are exactly the caller's.
 */
public class UniquelyNamedMultipartFile implements MultipartFile {

    private final MultipartFile delegate;
    private final String uniqueName;

    public UniquelyNamedMultipartFile(MultipartFile delegate) {
        this.delegate = delegate;
        this.uniqueName = StorageKeys.uniquify(delegate.getOriginalFilename());
    }

    /** The caller's real filename, restored onto the entity after the row is written. */
    public String realFilename() {
        return delegate.getOriginalFilename();
    }

    @Override
    public String getOriginalFilename() {
        return uniqueName;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getContentType() {
        return delegate.getContentType();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public long getSize() {
        return delegate.getSize();
    }

    @Override
    public byte[] getBytes() throws IOException {
        return delegate.getBytes();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return delegate.getInputStream();
    }

    @Override
    public void transferTo(File dest) throws IOException, IllegalStateException {
        delegate.transferTo(dest);
    }
}
