package com.naqqa.elasticsearch.codec;

public final class Codec {

    public static final String NAME = "Naqqa99";

    public static final String TERMS_DICT_EXT = "tdi";
    public static final String POSTINGS_EXT = "doc";
    public static final String STORED_FIELDS_EXT = "fld";
    public static final String DOC_VALUES_EXT = "dvd";
    public static final String POINTS_EXT = "dii";
    public static final String NORMS_EXT = "nrm";
    public static final String TERM_VECTORS_EXT = "tvd";
    public static final String LIVE_DOCS_EXT = "liv";
    public static final String FIELD_INFOS_EXT = "fnm";
    public static final String SEGMENT_INFO_EXT = "si";
    public static final String VECTORS_EXT = "vec";

    private Codec() {
    }

    public static String segmentFileName(String segmentName, String extension) {
        return segmentName + "." + extension;
    }

    public static String fieldFileName(String segmentName, String fieldName, String extension) {
        return segmentName + "_" + fieldName + "." + extension;
    }

    public static String termsDictFileName(String segmentName, String fieldName) {
        return fieldFileName(segmentName, fieldName, TERMS_DICT_EXT);
    }

    public static String postingsFileName(String segmentName, String fieldName) {
        return fieldFileName(segmentName, fieldName, POSTINGS_EXT);
    }

    public static String storedFieldsFileName(String segmentName) {
        return segmentFileName(segmentName, STORED_FIELDS_EXT);
    }

    public static String docValuesFileName(String segmentName, String fieldName) {
        return fieldFileName(segmentName, fieldName, DOC_VALUES_EXT);
    }

    public static String pointsFileName(String segmentName, String fieldName) {
        return fieldFileName(segmentName, fieldName, POINTS_EXT);
    }

    public static String normsFileName(String segmentName, String fieldName) {
        return fieldFileName(segmentName, fieldName, NORMS_EXT);
    }

    public static String termVectorsFileName(String segmentName) {
        return segmentFileName(segmentName, TERM_VECTORS_EXT);
    }

    public static String liveDocsFileName(String segmentName, long delGeneration) {
        return segmentName + "_" + Long.toString(delGeneration, Character.MAX_RADIX) + "." + LIVE_DOCS_EXT;
    }

    public static String fieldInfosFileName(String segmentName) {
        return segmentFileName(segmentName, FIELD_INFOS_EXT);
    }

    public static String segmentInfoFileName(String segmentName) {
        return segmentFileName(segmentName, SEGMENT_INFO_EXT);
    }

    public static String vectorsFileName(String segmentName, String fieldName) {
        return fieldFileName(segmentName, fieldName, VECTORS_EXT);
    }
}
