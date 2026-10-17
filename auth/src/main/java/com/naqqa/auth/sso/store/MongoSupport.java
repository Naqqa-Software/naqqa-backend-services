package com.naqqa.auth.sso.store;

import org.bson.Document;
import org.bson.types.Binary;

import java.time.Instant;
import java.util.Date;

public final class MongoSupport {

    private MongoSupport() {
    }

    public static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    public static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }

    public static byte[] bytes(Object value) {
        if (value instanceof Binary binary) {
            return binary.getData();
        }
        return (byte[]) value;
    }

    public static int integer(Document doc, String key) {
        Number number = doc.get(key, Number.class);
        return number == null ? 0 : number.intValue();
    }

    public static long number(Document doc, String key) {
        Number number = doc.get(key, Number.class);
        return number == null ? 0L : number.longValue();
    }

    public static boolean flag(Document doc, String key) {
        return Boolean.TRUE.equals(doc.getBoolean(key));
    }
}
