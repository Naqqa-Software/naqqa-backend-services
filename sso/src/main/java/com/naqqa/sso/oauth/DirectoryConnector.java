package com.naqqa.sso.oauth;

import java.util.List;
import java.util.Map;

public interface DirectoryConnector {
    record DirectoryUser(String dn, Map<String, List<String>> attributes) {
        public DirectoryUser {
            attributes = Map.copyOf(attributes);
        }
    }

    record SearchQuery(String baseDn, String attribute, String value, List<String> returnAttributes, int sizeLimit) {
        public SearchQuery {
            returnAttributes = List.copyOf(returnAttributes);
        }
    }

    DirectoryUser bind(String username, char[] password);

    List<DirectoryUser> search(SearchQuery query);
}
