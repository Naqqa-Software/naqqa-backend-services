package com.naqqa.elasticsearch.search.vectors.segment;

public enum QuantizationMode {
    NONE,
    INT8,
    INT4,
    BINARY;

    public byte code() {
        return (byte) ordinal();
    }

    public static QuantizationMode fromCode(byte code) {
        for (QuantizationMode mode : values()) {
            if (mode.ordinal() == code) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown quantization mode code " + code);
    }
}
