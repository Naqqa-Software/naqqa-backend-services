package com.naqqa.registry.web;

import com.naqqa.commons.web.PageResponse;
import com.naqqa.registry.dto.RegistryDtos.IssueTokenRequest;
import com.naqqa.registry.dto.RegistryDtos.IssuedToken;
import com.naqqa.registry.dto.RegistryDtos.RegistryCatalog;
import com.naqqa.registry.dto.RegistryDtos.RevokeTokenRequest;
import com.naqqa.registry.dto.RegistryDtos.TokenValidation;
import com.naqqa.registry.dto.RegistryDtos.TokenView;
import com.naqqa.registry.service.RegistryErrors;
import com.naqqa.registry.service.RegistryService;
import com.naqqa.registry.spi.RegistryAdminAccess;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${naqqa.registry.path:/api/registry}")
public class RegistryAdminController {

    private final RegistryService service;
    private final RegistryAdminAccess access;

    public RegistryAdminController(RegistryService service, RegistryAdminAccess access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/tokens")
    public ResponseEntity<IssuedToken> issue(@RequestBody(required = false) IssueTokenRequest request) {
        String admin = requireManage();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.issue(request, admin));
    }

    @GetMapping("/tokens")
    public PageResponse<TokenView> list(@RequestParam(required = false) String customerId,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        requireRead();
        return service.list(customerId, status, page, size);
    }

    @GetMapping("/tokens/{id}")
    public TokenView get(@PathVariable String id) {
        requireRead();
        return service.get(id);
    }

    @PostMapping("/tokens/{id}/revoke")
    public TokenView revoke(@PathVariable String id, @RequestBody(required = false) RevokeTokenRequest request) {
        String admin = requireManage();
        return service.revoke(id, request == null ? null : request.reason(), admin);
    }

    @GetMapping("/catalog")
    public RegistryCatalog catalog() {
        requireRead();
        return service.describe();
    }

    @GetMapping("/validate")
    public ResponseEntity<TokenValidation> validate(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.validate(authorization));
    }

    private String requireRead() {
        String admin = access.currentAdmin().orElseThrow(RegistryErrors::adminRequired);
        if (!access.canRead()) {
            throw RegistryErrors.forbidden();
        }
        return admin;
    }

    private String requireManage() {
        String admin = access.currentAdmin().orElseThrow(RegistryErrors::adminRequired);
        if (!access.canManage()) {
            throw RegistryErrors.forbidden();
        }
        return admin;
    }
}
