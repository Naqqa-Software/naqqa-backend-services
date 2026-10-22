package com.naqqa.filestorage.support;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class UniquelyNamedMultipartFile implements MultipartFile {

    private final MultipartFile delegate;
    private final String uniqueName;

    public UniquelyNamedMultipartFile(MultipartFile delegate) {
        this.delegate = delegate;
        this.uniqueName = StorageKeys.uniquify(delegate.getOriginalFilename());
    }

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
