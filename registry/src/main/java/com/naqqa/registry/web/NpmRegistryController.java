package com.naqqa.registry.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.naqqa.registry.core.PackageNames;
import com.naqqa.registry.domain.PackageEntry;
import com.naqqa.registry.domain.RegistryToken;
import com.naqqa.registry.dto.RegistryDtos.WhoAmI;
import com.naqqa.registry.service.NpmPackuments;
import com.naqqa.registry.service.RegistryErrors;
import com.naqqa.registry.service.RegistryService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("${naqqa.registry.path:/api/registry}/npm")
public class NpmRegistryController {

    static final String SCOPE = "{scope:@[a-z0-9][a-z0-9._-]*}";
    static final String NAME = "{name:[a-z0-9][a-z0-9._-]*}";

    private final RegistryService service;
    private final NpmPackuments packuments;
    private final String npmPath;
    private final String publicUrl;

    public NpmRegistryController(RegistryService service, NpmPackuments packuments, String basePath, String publicUrl) {
        this.service = service;
        this.packuments = packuments;
        this.npmPath = stripTrailingSlash(basePath) + "/npm";
        this.publicUrl = publicUrl == null || publicUrl.isBlank() ? null : stripTrailingSlash(publicUrl.trim());
    }

    @GetMapping("/-/ping")
    public Map<String, Object> ping() {
        return Map.of();
    }

    @GetMapping("/-/whoami")
    public ResponseEntity<WhoAmI> whoami(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        RegistryToken token = service.authenticate(authorization);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new WhoAmI(token.customerId()));
    }

    @GetMapping("/" + NAME)
    public ResponseEntity<ObjectNode> unscoped(@PathVariable String name,
                                               @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return metadata(name, authorization);
    }

    @GetMapping("/" + SCOPE + "/" + NAME)
    public ResponseEntity<ObjectNode> scoped(@PathVariable String scope, @PathVariable String name,
                                             @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return metadata(scope + "/" + name, authorization);
    }

    @GetMapping("/" + NAME + "/-/{file:.+}")
    public ResponseEntity<Resource> unscopedTarball(@PathVariable String name, @PathVariable String file,
                                                    @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) throws IOException {
        return tarball(name, file, authorization);
    }

    @GetMapping("/" + SCOPE + "/" + NAME + "/-/{file:.+}")
    public ResponseEntity<Resource> scopedTarball(@PathVariable String scope, @PathVariable String name, @PathVariable String file,
                                                  @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) throws IOException {
        return tarball(scope + "/" + name, file, authorization);
    }

    private ResponseEntity<ObjectNode> metadata(String name, String authorization) {
        RegistryToken token = service.authenticate(authorization);
        PackageEntry entry = service.grantedPackage(token, name).orElseThrow(RegistryErrors::packageNotFound);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().cachePrivate())
                .varyBy(HttpHeaders.AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .body(packuments.packument(entry, npmBaseUrl()));
    }

    private ResponseEntity<Resource> tarball(String name, String file, String authorization) throws IOException {
        RegistryToken token = service.authenticate(authorization);
        PackageEntry entry = service.grantedPackage(token, name).orElseThrow(RegistryErrors::packageNotFound);
        String prefix = PackageNames.baseName(name) + "-";
        if (!file.startsWith(prefix) || !file.endsWith(".tgz")) {
            throw RegistryErrors.packageNotFound();
        }
        String version = file.substring(prefix.length(), file.length() - ".tgz".length());
        if (!entry.versions().containsKey(version)) {
            throw RegistryErrors.packageNotFound();
        }
        Resource resource = service.tarball(name, version).orElseThrow(RegistryErrors::packageNotFound);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .varyBy(HttpHeaders.AUTHORIZATION)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(resource.contentLength())
                .body(resource);
    }

    private String npmBaseUrl() {
        String origin = publicUrl != null ? publicUrl : ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return stripTrailingSlash(origin) + npmPath;
    }

    private static String stripTrailingSlash(String value) {
        String v = value;
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
