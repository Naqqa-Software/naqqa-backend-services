package com.naqqa.elasticsearch.ingest;

public abstract class AbstractProcessor implements Processor {

    private final String type;
    private final String tag;
    private final String description;

    protected AbstractProcessor(String type, String tag, String description) {
        this.type = type;
        this.tag = tag;
        this.description = description;
    }

    @Override
    public final String getType() {
        return type;
    }

    @Override
    public final String getTag() {
        return tag;
    }

    @Override
    public final String getDescription() {
        return description;
    }
}
