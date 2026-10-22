package com.naqqa.observability.spi;

public interface ObservabilityAccess {

    ObservabilityAccess ALLOW_ALL = new ObservabilityAccess() {
        @Override
        public boolean can(String projectId, String scope) {
            return true;
        }

        @Override
        public boolean canManage(String projectId) {
            return true;
        }
    };

    boolean can(String projectId, String scope);

    boolean canManage(String projectId);
}
