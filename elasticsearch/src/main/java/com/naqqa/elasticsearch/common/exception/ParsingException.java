package com.naqqa.elasticsearch.common.exception;

public class ParsingException extends ElasticsearchException {

    private final int line;
    private final int columnNumber;

    public ParsingException(int line, int col, String msg, Object... args) {
        super(msg, args);
        this.line = line;
        this.columnNumber = col;
    }

    public ParsingException(int line, int col, String msg, Throwable cause) {
        super(msg, cause);
        this.line = line;
        this.columnNumber = col;
    }

    public int getLineNumber() {
        return line;
    }

    public int getColumnNumber() {
        return columnNumber;
    }

    @Override
    public RestStatus status() {
        return RestStatus.BAD_REQUEST;
    }
}
