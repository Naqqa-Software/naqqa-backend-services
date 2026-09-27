package com.naqqa.elasticsearch.ingest;

import java.util.List;

public final class CompoundProcessor implements Processor {

    private final Processor inner;
    private final List<Processor> onFailureProcessors;
    private final boolean ignoreFailure;

    public CompoundProcessor(Processor inner, List<Processor> onFailureProcessors, boolean ignoreFailure) {
        this.inner = inner;
        this.onFailureProcessors = onFailureProcessors;
        this.ignoreFailure = ignoreFailure;
    }

    @Override
    public IngestDocument execute(IngestDocument document) throws Exception {
        try {
            return inner.execute(document);
        } catch (Exception e) {
            if (onFailureProcessors != null && !onFailureProcessors.isEmpty()) {
                document.setFieldValue(IngestDocument.INGEST_KEY + "." + IngestDocument.ON_FAILURE_MESSAGE, e.getMessage());
                document.setFieldValue(IngestDocument.INGEST_KEY + "." + IngestDocument.ON_FAILURE_PROCESSOR_TYPE, inner.getType());
                if (inner.getTag() != null) {
                    document.setFieldValue(IngestDocument.INGEST_KEY + "." + IngestDocument.ON_FAILURE_PROCESSOR_TAG, inner.getTag());
                }
                IngestDocument result = document;
                for (Processor p : onFailureProcessors) {
                    result = p.execute(result);
                    if (result == null) {
                        return null;
                    }
                }
                return result;
            }
            if (ignoreFailure) {
                return document;
            }
            throw new IngestProcessorException(inner.getType(), inner.getTag(), e);
        }
    }

    @Override
    public String getType() {
        return inner.getType();
    }

    @Override
    public String getTag() {
        return inner.getTag();
    }

    @Override
    public String getDescription() {
        return inner.getDescription();
    }

    public Processor getInnerProcessor() {
        return inner;
    }
}
