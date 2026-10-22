package com.naqqa.sso.saml.xml;

public record XmlLimits(int maxBytes, int maxDepth, int maxAttributes, int maxNodes, int maxNameLength) {
    public static final XmlLimits DEFAULT = new XmlLimits(256 * 1024, 32, 64, 10000, 256);

    public XmlLimits {
        if (maxBytes <= 0 || maxDepth <= 0 || maxAttributes <= 0 || maxNodes <= 0 || maxNameLength <= 0) {
            throw new IllegalArgumentException("invalid xml limits");
        }
    }

    public XmlLimits withMaxBytes(int bytes) {
        return new XmlLimits(bytes, maxDepth, maxAttributes, maxNodes, maxNameLength);
    }
}
