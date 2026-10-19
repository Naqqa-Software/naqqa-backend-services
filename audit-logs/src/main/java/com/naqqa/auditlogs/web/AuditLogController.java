package com.naqqa.auditlogs.web;

import com.naqqa.auditlogs.dto.UserLogsPage;
import com.naqqa.auditlogs.service.AuditLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${naqqa.audit-logs.path:/api/logs}")
public class AuditLogController {

    private final AuditLogService service;

    public AuditLogController(AuditLogService service) {
        this.service = service;
    }

    @GetMapping
    public UserLogsPage list(@RequestParam(value = "page", defaultValue = "0") int page,
                             @RequestParam(value = "size", defaultValue = "20") int size,
                             @RequestParam(value = "domain", required = false) String domain,
                             @RequestParam(value = "type", required = false) String type) {
        return service.list(page, size, domain, type);
    }
}
