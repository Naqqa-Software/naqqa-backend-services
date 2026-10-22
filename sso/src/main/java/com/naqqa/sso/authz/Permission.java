package com.naqqa.sso.authz;

import com.naqqa.sso.SsoException;

public record Permission(String resource, String action) {
    public Permission {
        if (resource == null || action == null || resource.isEmpty() || action.isEmpty()
                || !resource.matches("[A-Za-z0-9_.*/-]+") || !action.matches("[A-Za-z0-9_.*-]+")) {
            throw new SsoException("authz_invalid_permission");
        }
    }

    public static Permission parse(String text) {
        if (text == null) {
            throw new SsoException("authz_invalid_permission");
        }
        int idx = text.indexOf(':');
        if (idx <= 0 || idx != text.lastIndexOf(':') || idx == text.length() - 1) {
            throw new SsoException("authz_invalid_permission");
        }
        return new Permission(text.substring(0, idx), text.substring(idx + 1));
    }

    public boolean isConcrete() {
        return resource.indexOf('*') < 0 && action.indexOf('*') < 0;
    }

    public boolean implies(Permission requested) {
        return segmentMatch(resource, requested.resource) && segmentMatch(action, requested.action);
    }

    private static boolean segmentMatch(String pattern, String value) {
        if (pattern.equals("*")) {
            return true;
        }
        if (pattern.endsWith(".*") || pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return value.startsWith(prefix) && value.length() > prefix.length();
        }
        return pattern.equals(value);
    }

    @Override
    public String toString() {
        return resource + ":" + action;
    }
}
