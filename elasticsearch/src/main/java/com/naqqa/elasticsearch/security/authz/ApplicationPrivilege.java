package com.naqqa.elasticsearch.security.authz;

import java.util.List;

public record ApplicationPrivilege(String application, List<String> privileges, List<String> resources) {
}
