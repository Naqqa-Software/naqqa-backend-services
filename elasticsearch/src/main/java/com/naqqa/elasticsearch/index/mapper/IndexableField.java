package com.naqqa.elasticsearch.index.mapper;

import java.util.List;

public final class IndexableField {

    public enum Kind {
        INDEXED_TEXT, SORTED_SET_DOC_VALUES, NUMERIC_DOC_VALUES, BINARY_DOC_VALUES, POINT, STORED, VECTOR
    }

    private final String name;
    private final Kind kind;
    private final boolean indexed;
    private final boolean stored;
    private final boolean docValues;
    private final boolean points;
    private final boolean norms;
    private final List<IndexedTerm> terms;
    private final List<byte[]> sortedSetValues;
    private final long numericValue;
    private final byte[] binaryValue;
    private final byte[][] pointDims;
    private final float[] vectorFloats;
    private final byte[] vectorBytes;
    private final String vectorElementType;
    private final int vectorDims;
    private final String vectorSimilarity;

    private IndexableField(String name, Kind kind, boolean indexed, boolean stored, boolean docValues, boolean points, boolean norms,
                            List<IndexedTerm> terms, List<byte[]> sortedSetValues, long numericValue, byte[] binaryValue,
                            byte[][] pointDims, float[] vectorFloats, byte[] vectorBytes, String vectorElementType,
                            int vectorDims, String vectorSimilarity) {
        this.name = name;
        this.kind = kind;
        this.indexed = indexed;
        this.stored = stored;
        this.docValues = docValues;
        this.points = points;
        this.norms = norms;
        this.terms = terms;
        this.sortedSetValues = sortedSetValues;
        this.numericValue = numericValue;
        this.binaryValue = binaryValue;
        this.pointDims = pointDims;
        this.vectorFloats = vectorFloats;
        this.vectorBytes = vectorBytes;
        this.vectorElementType = vectorElementType;
        this.vectorDims = vectorDims;
        this.vectorSimilarity = vectorSimilarity;
    }

    public static IndexableField indexedText(String name, List<IndexedTerm> terms, boolean norms) {
        return new IndexableField(name, Kind.INDEXED_TEXT, true, false, false, false, norms,
            List.copyOf(terms), null, 0, null, null, null, null, null, 0, null);
    }

    public static IndexableField sortedSetDocValues(String name, List<byte[]> values) {
        return new IndexableField(name, Kind.SORTED_SET_DOC_VALUES, false, false, true, false, false,
            null, List.copyOf(values), 0, null, null, null, null, null, 0, null);
    }

    public static IndexableField numericDocValue(String name, long value) {
        return new IndexableField(name, Kind.NUMERIC_DOC_VALUES, false, false, true, false, false,
            null, null, value, null, null, null, null, null, 0, null);
    }

    public static IndexableField binaryDocValue(String name, byte[] value) {
        return new IndexableField(name, Kind.BINARY_DOC_VALUES, false, false, true, false, false,
            null, null, 0, value, null, null, null, null, 0, null);
    }

    public static IndexableField point(String name, byte[][] dims) {
        return new IndexableField(name, Kind.POINT, true, false, false, true, false,
            null, null, 0, null, dims, null, null, null, 0, null);
    }

    public static IndexableField stored(String name, byte[] value) {
        return new IndexableField(name, Kind.STORED, false, true, false, false, false,
            null, null, 0, value, null, null, null, null, 0, null);
    }

    public static IndexableField vector(String name, float[] floats, byte[] rawBytes, String elementType, int dims, String similarity) {
        return new IndexableField(name, Kind.VECTOR, false, true, false, false, false,
            null, null, 0, null, null, floats, rawBytes, elementType, dims, similarity);
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    public boolean indexed() {
        return indexed;
    }

    public boolean stored() {
        return stored;
    }

    public boolean docValues() {
        return docValues;
    }

    public boolean points() {
        return points;
    }

    public boolean norms() {
        return norms;
    }

    public List<IndexedTerm> terms() {
        return terms;
    }

    public List<byte[]> sortedSetValues() {
        return sortedSetValues;
    }

    public long numericValue() {
        return numericValue;
    }

    public byte[] binaryValue() {
        return binaryValue;
    }

    public byte[][] pointDims() {
        return pointDims;
    }

    public float[] vectorFloats() {
        return vectorFloats;
    }

    public byte[] vectorBytes() {
        return vectorBytes;
    }

    public String vectorElementType() {
        return vectorElementType;
    }

    public int vectorDims() {
        return vectorDims;
    }

    public String vectorSimilarity() {
        return vectorSimilarity;
    }

    @Override
    public String toString() {
        return "IndexableField{" + name + "," + kind + "}";
    }
}
