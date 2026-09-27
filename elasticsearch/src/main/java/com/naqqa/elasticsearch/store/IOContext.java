package com.naqqa.elasticsearch.store;

public record IOContext(Context context, boolean readOnce, boolean verifyChecksumsOnOpen, long expectedBytes) {

    public enum Context {
        DEFAULT, READ, FLUSH, MERGE
    }

    public static final IOContext DEFAULT = new IOContext(Context.DEFAULT, false, true, 0);
    public static final IOContext READ = new IOContext(Context.READ, false, true, 0);
    public static final IOContext READ_NO_VERIFY = new IOContext(Context.READ, false, false, 0);
    public static final IOContext READONCE = new IOContext(Context.READ, true, true, 0);
    public static final IOContext FLUSH = new IOContext(Context.FLUSH, false, true, 0);
    public static final IOContext MERGE = new IOContext(Context.MERGE, false, true, 0);

    public static IOContext merge(long expectedBytes) {
        return new IOContext(Context.MERGE, false, true, expectedBytes);
    }

    public static IOContext flush(long expectedBytes) {
        return new IOContext(Context.FLUSH, false, true, expectedBytes);
    }

    public IOContext withVerify(boolean verify) {
        return new IOContext(context, readOnce, verify, expectedBytes);
    }
}
