package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;

import java.util.List;

public final class UnsupportedDirectoryConnector implements DirectoryConnector {
    @Override
    public DirectoryUser bind(String username, char[] password) {
        throw new SsoException("oauth.directory_unsupported");
    }

    @Override
    public List<DirectoryUser> search(SearchQuery query) {
        throw new SsoException("oauth.directory_unsupported");
    }
}
