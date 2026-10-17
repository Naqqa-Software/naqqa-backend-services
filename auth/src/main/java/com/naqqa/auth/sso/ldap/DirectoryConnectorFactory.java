package com.naqqa.auth.sso.ldap;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.sso.oauth.DirectoryConnector;

public interface DirectoryConnectorFactory {

    DirectoryConnector connector(String name, SsoProperties.Directory directory);
}
