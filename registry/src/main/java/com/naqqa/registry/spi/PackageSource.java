package com.naqqa.registry.spi;

import com.naqqa.registry.domain.PackageCatalog;
import org.springframework.core.io.Resource;

import java.util.Optional;

public interface PackageSource {

    PackageCatalog catalog();

    Optional<Resource> tarball(String name, String version);
}
