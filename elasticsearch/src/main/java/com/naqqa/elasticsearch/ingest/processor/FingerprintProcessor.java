package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class FingerprintProcessor extends AbstractProcessor {

    public static final String TYPE = "fingerprint";

    private final List<String> fields;
    private final String targetField;
    private final String salt;
    private final String method;

    public FingerprintProcessor(String tag, String description, List<String> fields, String targetField, String salt, String method) {
        super(TYPE, tag, description);
        this.fields = fields;
        this.targetField = targetField;
        this.salt = salt;
        this.method = method;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        List<String> effectiveFields = fields;
        if (effectiveFields == null || effectiveFields.isEmpty()) {
            effectiveFields = new ArrayList<>(new TreeMap<>(document.getSource()).keySet());
        } else {
            effectiveFields = new ArrayList<>(effectiveFields);
            Collections.sort(effectiveFields);
        }
        StringBuilder sb = new StringBuilder();
        if (salt != null) {
            sb.append(salt);
        }
        for (String field : effectiveFields) {
            if (!document.hasField(field)) {
                continue;
            }
            sb.append('|').append(field).append('=').append(document.getFieldValue(field, Object.class));
        }
        String canonical = sb.toString();
        String fingerprint = method.equalsIgnoreCase("murmur3")
            ? method.toUpperCase() + ":" + Base64.getEncoder().encodeToString(Murmur3.hash128(canonical, 0))
            : method.toUpperCase() + ":" + Base64.getEncoder().encodeToString(digest(canonical, method));
        document.setFieldValue(document.renderTemplate(targetField), fingerprint);
        return document;
    }

    private static byte[] digest(String input, String method) {
        String algo = switch (method.toLowerCase()) {
            case "sha-1", "sha1" -> "SHA-1";
            case "sha-256", "sha256" -> "SHA-256";
            case "sha-512", "sha512" -> "SHA-512";
            case "md5" -> "MD5";
            default -> throw new IllegalArgumentException("unsupported fingerprint method [" + method + "]");
        };
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot compute fingerprint", e);
        }
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            List<String> fields = ConfigurationUtils.readOptionalStringList(config, "fields");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", "fingerprint");
            String salt = ConfigurationUtils.readOptionalStringProperty(config, "salt");
            String method = ConfigurationUtils.readStringProperty(TYPE, tag, config, "method", "SHA-256");
            return new FingerprintProcessor(tag, description, fields, targetField, salt, method);
        }
    }
}
