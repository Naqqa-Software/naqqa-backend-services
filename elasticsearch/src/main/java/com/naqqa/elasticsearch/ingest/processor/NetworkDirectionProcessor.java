package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.List;
import java.util.Map;

public final class NetworkDirectionProcessor extends AbstractProcessor {

    public static final String TYPE = "network_direction";

    private final String sourceIpField;
    private final String destinationIpField;
    private final List<String> internalNetworks;
    private final String targetField;

    public NetworkDirectionProcessor(String tag, String description, String sourceIpField, String destinationIpField,
                                      List<String> internalNetworks, String targetField) {
        super(TYPE, tag, description);
        this.sourceIpField = sourceIpField;
        this.destinationIpField = destinationIpField;
        this.internalNetworks = internalNetworks;
        this.targetField = targetField;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String sourceIp = document.getFieldValue(sourceIpField, String.class, true);
        String destinationIp = document.getFieldValue(destinationIpField, String.class, true);
        if (sourceIp == null || destinationIp == null) {
            return document;
        }
        boolean sourceInternal = IpUtils.matchesAny(sourceIp, internalNetworks);
        boolean destinationInternal = IpUtils.matchesAny(destinationIp, internalNetworks);
        String direction;
        if (sourceInternal && destinationInternal) {
            direction = "internal";
        } else if (sourceInternal) {
            direction = "outbound";
        } else if (destinationInternal) {
            direction = "inbound";
        } else {
            direction = "external";
        }
        document.setFieldValue(document.renderTemplate(targetField), direction);
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String sourceIpField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "source_ip", "source.ip");
            String destinationIpField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "destination_ip", "destination.ip");
            List<String> internalNetworks = ConfigurationUtils.readOptionalStringList(config, "internal_networks");
            if (internalNetworks == null) {
                internalNetworks = List.of("private");
            }
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", "network.direction");
            return new NetworkDirectionProcessor(tag, description, sourceIpField, destinationIpField, internalNetworks, targetField);
        }
    }
}
