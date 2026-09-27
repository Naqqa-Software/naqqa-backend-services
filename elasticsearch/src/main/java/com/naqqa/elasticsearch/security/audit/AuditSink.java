package com.naqqa.elasticsearch.security.audit;

public interface AuditSink {

    void write(String jsonLine);
}
