package com.naqqa.elasticsearch.common.json;

final class JsonScalar implements JsonValue {

    private final Object value;

    JsonScalar(Object value) {
        this.value = value;
    }

    Object value() {
        return value;
    }

    @Override
    public Object toJava() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JsonScalar other)) {
            return false;
        }
        return java.util.Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return value == null ? "null" : value.toString();
    }
}
