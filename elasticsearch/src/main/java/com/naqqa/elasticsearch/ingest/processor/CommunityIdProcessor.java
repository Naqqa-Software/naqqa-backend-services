package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

public final class CommunityIdProcessor extends AbstractProcessor {

    public static final String TYPE = "community_id";

    private final String sourceIpField;
    private final String sourcePortField;
    private final String destIpField;
    private final String destPortField;
    private final String ianaNumberField;
    private final String transportField;
    private final String icmpTypeField;
    private final String icmpCodeField;
    private final String targetField;
    private final int seed;

    public CommunityIdProcessor(String tag, String description, String sourceIpField, String sourcePortField,
                                 String destIpField, String destPortField, String ianaNumberField, String transportField,
                                 String icmpTypeField, String icmpCodeField, String targetField, int seed) {
        super(TYPE, tag, description);
        this.sourceIpField = sourceIpField;
        this.sourcePortField = sourcePortField;
        this.destIpField = destIpField;
        this.destPortField = destPortField;
        this.ianaNumberField = ianaNumberField;
        this.transportField = transportField;
        this.icmpTypeField = icmpTypeField;
        this.icmpCodeField = icmpCodeField;
        this.targetField = targetField;
        this.seed = seed;
    }

    private static final Map<String, Integer> TRANSPORT_TO_IANA = Map.of(
        "tcp", 6, "udp", 17, "icmp", 1, "icmpv6", 58, "sctp", 132
    );

    @Override
    public IngestDocument execute(IngestDocument document) {
        String srcIp = document.getFieldValue(sourceIpField, String.class, true);
        String dstIp = document.getFieldValue(destIpField, String.class, true);
        if (srcIp == null || dstIp == null) {
            return document;
        }
        Integer proto = readInt(document, ianaNumberField);
        if (proto == null) {
            String transport = document.getFieldValue(transportField, String.class, true);
            if (transport != null) {
                proto = TRANSPORT_TO_IANA.get(transport.toLowerCase());
            }
        }
        if (proto == null) {
            return document;
        }
        Integer srcPort = readInt(document, sourcePortField);
        Integer dstPort = readInt(document, destPortField);

        try {
            byte[] srcAddr = InetAddress.getByName(srcIp).getAddress();
            byte[] dstAddr = InetAddress.getByName(dstIp).getAddress();
            int portOne;
            int portTwo;
            if (proto == 1 || proto == 58) {
                Integer icmpType = readInt(document, icmpTypeField);
                Integer icmpCode = readInt(document, icmpCodeField);
                portOne = icmpType == null ? 0 : mapIcmpType(icmpType);
                portTwo = icmpCode == null ? 0 : icmpCode;
            } else {
                if (srcPort == null || dstPort == null) {
                    return document;
                }
                portOne = srcPort;
                portTwo = dstPort;
            }

            boolean swap = compareEndpoints(srcAddr, portOne, dstAddr, portTwo) > 0;
            byte[] lowAddr = swap ? dstAddr : srcAddr;
            byte[] highAddr = swap ? srcAddr : dstAddr;
            int lowPort = swap ? portTwo : portOne;
            int highPort = swap ? portOne : portTwo;

            ByteBuffer buffer = ByteBuffer.allocate(2 + lowAddr.length + highAddr.length + 2 + 2 + 2);
            buffer.putShort((short) seed);
            buffer.put(lowAddr);
            buffer.put(highAddr);
            buffer.put((byte) proto.intValue());
            buffer.put((byte) 0);
            buffer.putShort((short) lowPort);
            buffer.putShort((short) highPort);

            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(buffer.array());
            String communityId = "1:" + Base64.getEncoder().encodeToString(digest);
            document.setFieldValue(document.renderTemplate(targetField), communityId);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to compute community_id", e);
        }
        return document;
    }

    private static int mapIcmpType(int type) {
        return switch (type) {
            case 0 -> 8;
            case 14 -> 13;
            case 16 -> 15;
            case 18 -> 17;
            default -> type;
        };
    }

    private static int compareEndpoints(byte[] addrA, int portA, byte[] addrB, int portB) {
        int cmp = compareBytes(addrA, addrB);
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(portA, portB);
    }

    private static int compareBytes(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int cmp = Integer.compare(a[i] & 0xFF, b[i] & 0xFF);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static Integer readInt(IngestDocument document, String field) {
        if (field == null || !document.hasField(field)) {
            return null;
        }
        Object value = document.getFieldValue(field, Object.class, true);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String sourceIpField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "source_ip", "source.ip");
            String sourcePortField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "source_port", "source.port");
            String destIpField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "destination_ip", "destination.ip");
            String destPortField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "destination_port", "destination.port");
            String ianaNumberField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "iana_number", "network.iana_number");
            String transportField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "transport", "network.transport");
            String icmpTypeField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "icmp_type", "icmp.type");
            String icmpCodeField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "icmp_code", "icmp.code");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", "network.community_id");
            int seed = ConfigurationUtils.readIntProperty(TYPE, tag, config, "seed", 0);
            return new CommunityIdProcessor(tag, description, sourceIpField, sourcePortField, destIpField, destPortField,
                ianaNumberField, transportField, icmpTypeField, icmpCodeField, targetField, seed);
        }
    }
}
