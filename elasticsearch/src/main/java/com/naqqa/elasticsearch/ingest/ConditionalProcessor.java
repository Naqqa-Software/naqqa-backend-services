package com.naqqa.elasticsearch.ingest;

public final class ConditionalProcessor implements Processor {

    private final Processor inner;
    private final IngestScriptService.Condition condition;

    public ConditionalProcessor(Processor inner, IngestScriptService.Condition condition) {
        this.inner = inner;
        this.condition = condition;
    }

    public boolean testCondition(IngestDocument document) {
        return condition.test(document);
    }

    @Override
    public IngestDocument execute(IngestDocument document) throws Exception {
        if (!condition.test(document)) {
            return document;
        }
        return inner.execute(document);
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
