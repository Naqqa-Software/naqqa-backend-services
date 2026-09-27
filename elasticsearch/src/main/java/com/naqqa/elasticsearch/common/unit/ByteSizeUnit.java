package com.naqqa.elasticsearch.common.unit;

public enum ByteSizeUnit {

    BYTES {
        @Override
        public long toBytes(long size) {
            return size;
        }
    },
    KB {
        @Override
        public long toBytes(long size) {
            return size * (1L << 10);
        }
    },
    MB {
        @Override
        public long toBytes(long size) {
            return size * (1L << 20);
        }
    },
    GB {
        @Override
        public long toBytes(long size) {
            return size * (1L << 30);
        }
    },
    TB {
        @Override
        public long toBytes(long size) {
            return size * (1L << 40);
        }
    },
    PB {
        @Override
        public long toBytes(long size) {
            return size * (1L << 50);
        }
    };

    public abstract long toBytes(long size);
}
