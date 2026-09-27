package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.Map;

public final class RegisteredDomainProcessor extends AbstractProcessor {

    public static final String TYPE = "registered_domain";

    private final String field;
    private final String registeredDomainField;
    private final String topLevelDomainField;
    private final String subdomainField;
    private final boolean ignoreMissing;

    public RegisteredDomainProcessor(String tag, String description, String field, String registeredDomainField,
                                      String topLevelDomainField, String subdomainField, boolean ignoreMissing) {
        super(TYPE, tag, description);
        this.field = field;
        this.registeredDomainField = registeredDomainField;
        this.topLevelDomainField = topLevelDomainField;
        this.subdomainField = subdomainField;
        this.ignoreMissing = ignoreMissing;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        String host = document.getFieldValue(resolvedField, String.class, ignoreMissing);
        if (host == null) {
            return document;
        }
        String[] labels = host.split("\\.");
        if (labels.length < 2) {
            document.setFieldValue(registeredDomainField, host);
            return document;
        }
        int n = labels.length;
        boolean multiLevel = n >= 3 && PublicSuffixList.isMultiLevelSuffix(labels[n - 2], labels[n - 1]);
        int suffixLabelCount = multiLevel ? 2 : 1;
        int registeredStart = n - suffixLabelCount - 1;
        if (registeredStart < 0) {
            registeredStart = 0;
        }
        StringBuilder tld = new StringBuilder();
        for (int i = n - suffixLabelCount; i < n; i++) {
            if (tld.length() > 0) {
                tld.append('.');
            }
            tld.append(labels[i]);
        }
        StringBuilder registeredDomain = new StringBuilder();
        for (int i = registeredStart; i < n; i++) {
            if (registeredDomain.length() > 0) {
                registeredDomain.append('.');
            }
            registeredDomain.append(labels[i]);
        }
        document.setFieldValue(registeredDomainField, registeredDomain.toString());
        document.setFieldValue(topLevelDomainField, tld.toString());
        if (registeredStart > 0) {
            StringBuilder subdomain = new StringBuilder();
            for (int i = 0; i < registeredStart; i++) {
                if (subdomain.length() > 0) {
                    subdomain.append('.');
                }
                subdomain.append(labels[i]);
            }
            document.setFieldValue(subdomainField, subdomain.toString());
        }
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String registeredDomainField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", "registered_domain");
            String topLevelDomainField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "top_level_domain_field", "top_level_domain");
            String subdomainField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "subdomain_field", "subdomain");
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing", false);
            return new RegisteredDomainProcessor(tag, description, field, registeredDomainField, topLevelDomainField, subdomainField, ignoreMissing);
        }
    }
}
