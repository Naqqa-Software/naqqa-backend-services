package com.naqqa.elasticsearch.script.painless;

final class BreakSignal extends RuntimeException {
    static final BreakSignal INSTANCE = new BreakSignal();

    private BreakSignal() {
        super(null, null, false, false);
    }
}

final class ContinueSignal extends RuntimeException {
    static final ContinueSignal INSTANCE = new ContinueSignal();

    private ContinueSignal() {
        super(null, null, false, false);
    }
}

final class ReturnSignal extends RuntimeException {
    final Object value;

    ReturnSignal(Object value) {
        super(null, null, false, false);
        this.value = value;
    }
}
