package com.naqqa.registry.spi;

import java.util.Optional;

public interface RegistryAdminAccess {

    RegistryAdminAccess DENY_ALL = new RegistryAdminAccess() {
        @Override
        public Optional<String> currentAdmin() {
            return Optional.empty();
        }

        @Override
        public boolean canRead() {
            return false;
        }

        @Override
        public boolean canManage() {
            return false;
        }
    };

    Optional<String> currentAdmin();

    boolean canRead();

    boolean canManage();
}
