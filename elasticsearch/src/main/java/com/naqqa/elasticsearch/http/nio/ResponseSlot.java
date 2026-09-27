package com.naqqa.elasticsearch.http.nio;

final class ResponseSlot {
    volatile byte[] bytes;
    volatile boolean ready;
    volatile boolean closeConnection;
}
