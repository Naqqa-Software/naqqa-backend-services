package com.naqqa.auditlogs.config;

import com.naqqa.auditlogs.service.AuditLogService;
import com.naqqa.auditlogs.spi.AuditLogSource;
import com.naqqa.auditlogs.spi.DevAuditLogSource;
import com.naqqa.auditlogs.web.AuditLogController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "naqqa.audit-logs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuditLogsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditLogSource naqqaAuditLogSource() {
        return new DevAuditLogSource();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogService naqqaAuditLogService(AuditLogSource source) {
        return new AuditLogService(source);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogController naqqaAuditLogController(AuditLogService service) {
        return new AuditLogController(service);
    }
}
