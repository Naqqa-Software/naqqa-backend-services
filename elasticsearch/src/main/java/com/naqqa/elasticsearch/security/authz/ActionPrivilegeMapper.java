package com.naqqa.elasticsearch.security.authz;

public final class ActionPrivilegeMapper {

    private ActionPrivilegeMapper() {
    }

    public static boolean isClusterAction(String action) {
        return action != null && action.startsWith("cluster:");
    }

    public static boolean isIndexAction(String action) {
        return action != null && action.startsWith("indices:");
    }
}
