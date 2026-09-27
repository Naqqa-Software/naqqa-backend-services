package com.naqqa.elasticsearch.http;

import javax.net.ssl.SSLContext;

public final class HttpServerConfig {

    private String host = "0.0.0.0";
    private int port = 0;
    private int maxInitialLineLength = 4096;
    private int maxHeaderBytes = 8192;
    private int maxHeaderCount = 100;
    private long maxBodyBytes = 100L * 1024 * 1024;
    private int backlog = 128;
    private int ioThreads = 1;
    private CorsConfig corsConfig = CorsConfig.disabled();
    private SSLContext sslContext;
    private boolean clientAuthRequired = false;
    private RestErrorRenderer errorRenderer = new DefaultRestErrorRenderer();

    public String host() {
        return host;
    }

    public HttpServerConfig host(String host) {
        this.host = host;
        return this;
    }

    public int port() {
        return port;
    }

    public HttpServerConfig port(int port) {
        this.port = port;
        return this;
    }

    public int maxInitialLineLength() {
        return maxInitialLineLength;
    }

    public HttpServerConfig maxInitialLineLength(int value) {
        this.maxInitialLineLength = value;
        return this;
    }

    public int maxHeaderBytes() {
        return maxHeaderBytes;
    }

    public HttpServerConfig maxHeaderBytes(int value) {
        this.maxHeaderBytes = value;
        return this;
    }

    public int maxHeaderCount() {
        return maxHeaderCount;
    }

    public HttpServerConfig maxHeaderCount(int value) {
        this.maxHeaderCount = value;
        return this;
    }

    public long maxBodyBytes() {
        return maxBodyBytes;
    }

    public HttpServerConfig maxBodyBytes(long value) {
        this.maxBodyBytes = value;
        return this;
    }

    public int backlog() {
        return backlog;
    }

    public HttpServerConfig backlog(int value) {
        this.backlog = value;
        return this;
    }

    public int ioThreads() {
        return ioThreads;
    }

    public HttpServerConfig ioThreads(int value) {
        this.ioThreads = value;
        return this;
    }

    public CorsConfig cors() {
        return corsConfig;
    }

    public HttpServerConfig cors(CorsConfig config) {
        this.corsConfig = config;
        return this;
    }

    public SSLContext sslContext() {
        return sslContext;
    }

    public HttpServerConfig sslContext(SSLContext context) {
        this.sslContext = context;
        return this;
    }

    public boolean clientAuthRequired() {
        return clientAuthRequired;
    }

    public HttpServerConfig clientAuthRequired(boolean value) {
        this.clientAuthRequired = value;
        return this;
    }

    public RestErrorRenderer errorRenderer() {
        return errorRenderer;
    }

    public HttpServerConfig errorRenderer(RestErrorRenderer renderer) {
        this.errorRenderer = renderer;
        return this;
    }
}
