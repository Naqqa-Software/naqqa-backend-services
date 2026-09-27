package com.naqqa.elasticsearch.http;

import java.io.IOException;
import java.net.InetSocketAddress;

public interface HttpServerTransport extends AutoCloseable {

    void start() throws IOException;

    InetSocketAddress boundAddress();

    @Override
    void close();
}
