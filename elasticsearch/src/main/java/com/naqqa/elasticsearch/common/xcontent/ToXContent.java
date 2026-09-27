package com.naqqa.elasticsearch.common.xcontent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public interface ToXContent {

    interface Params {
        String param(String key);

        default String param(String key, String defaultValue) {
            String value = param(key);
            return value == null ? defaultValue : value;
        }

        default boolean paramAsBoolean(String key, boolean defaultValue) {
            String value = param(key);
            if (value == null) {
                return defaultValue;
            }
            if (value.isEmpty() || value.equals("true")) {
                return true;
            }
            if (value.equals("false")) {
                return false;
            }
            throw new IllegalArgumentException("Failed to parse value [" + value + "] as only [true] or [false] are allowed.");
        }

        default Boolean paramAsBoolean(String key, Boolean defaultValue) {
            String value = param(key);
            if (value == null) {
                return defaultValue;
            }
            return paramAsBoolean(key, false);
        }
    }

    Params EMPTY_PARAMS = new MapParams(Collections.emptyMap());

    class MapParams implements Params {
        private final Map<String, String> params;

        public MapParams(Map<String, String> params) {
            this.params = params == null ? Collections.emptyMap() : new HashMap<>(params);
        }

        @Override
        public String param(String key) {
            return params.get(key);
        }
    }

    class DelegatingMapParams extends MapParams {
        private final Params delegate;

        public DelegatingMapParams(Map<String, String> params, Params delegate) {
            super(params);
            this.delegate = delegate;
        }

        @Override
        public String param(String key) {
            String value = super.param(key);
            return value != null ? value : delegate.param(key);
        }
    }

    XContentGenerator toXContent(XContentGenerator generator, Params params);

    default boolean isFragment() {
        return true;
    }
}
