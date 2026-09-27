package com.naqqa.elasticsearch.http.jdk;

import com.naqqa.elasticsearch.http.CompressionUtil;
import com.naqqa.elasticsearch.http.CorsHandler;
import com.naqqa.elasticsearch.http.HttpHeaders;
import com.naqqa.elasticsearch.http.HttpServerConfig;
import com.naqqa.elasticsearch.http.HttpServerTransport;
import com.naqqa.elasticsearch.http.RestChannel;
import com.naqqa.elasticsearch.http.RestErrors;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.http.RouteResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class JdkHttpServerTransport implements HttpServerTransport {

    private final HttpServerConfig config;
    private final Router router;
    private final CorsHandler corsHandler;
    private HttpServer server;
    private ExecutorService executor;

    public JdkHttpServerTransport(HttpServerConfig config, Router router) {
        this.config = config;
        this.router = router;
        this.corsHandler = new CorsHandler(config.cors());
    }

    @Override
    public void start() throws IOException {
        InetSocketAddress address = new InetSocketAddress(config.host(), config.port());
        if (config.sslContext() != null) {
            HttpsServer httpsServer = HttpsServer.create(address, config.backlog());
            SSLContext sslContext = config.sslContext();
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters sslParameters = sslContext.getDefaultSSLParameters();
                    if (config.clientAuthRequired()) {
                        sslParameters.setNeedClientAuth(true);
                    }
                    params.setSSLParameters(sslParameters);
                }
            });
            server = httpsServer;
        } else {
            server = HttpServer.create(address, config.backlog());
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    @Override
    public InetSocketAddress boundAddress() {
        return server.getAddress();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(1);
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    private void handle(HttpExchange exchange) {
        RestRequest request;
        try {
            request = buildRequest(exchange);
        } catch (PayloadTooLargeException e) {
            RestRequest partial = buildRequestMetadata(exchange, new byte[0]);
            try {
                writeResponse(exchange, partial, RestErrors.fromException(partial, config.errorRenderer(), e));
            } catch (IOException ignored) {
            }
            exchange.close();
            return;
        } catch (Exception e) {
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (IOException ignored) {
            }
            exchange.close();
            return;
        }
        try {
            Optional<RestResponse> preflight = corsHandler.handlePreflight(request);
            if (preflight.isPresent()) {
                writeResponse(exchange, request, preflight.get());
                return;
            }
            RouteResult routeResult = router.route(request.method(), request.path());
            switch (routeResult.outcome()) {
                case MATCHED -> {
                    request.setPathParams(routeResult.pathParams());
                    JdkRestChannel channel = new JdkRestChannel(exchange, request);
                    try {
                        routeResult.handler().handleRequest(request, channel);
                    } catch (Exception e) {
                        channel.sendResponse(RestErrors.fromException(request, config.errorRenderer(), e));
                    }
                }
                case METHOD_NOT_ALLOWED -> writeResponse(exchange, request,
                    RestErrors.methodNotAllowed(request, routeResult.allowedMethods()));
                default -> writeResponse(exchange, request,
                    RestErrors.noHandlerFound(request, request.rawUri(), request.method()));
            }
        } catch (Exception e) {
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (IOException ignored) {
            }
        } finally {
            exchange.close();
        }
    }

    private RestRequest buildRequest(HttpExchange exchange) throws IOException {
        byte[] body = readLimited(exchange.getRequestBody(), config.maxBodyBytes());
        return buildRequestMetadata(exchange, body);
    }

    private RestRequest buildRequestMetadata(HttpExchange exchange, byte[] body) {
        com.naqqa.elasticsearch.http.RestMethod method = com.naqqa.elasticsearch.http.RestMethod.parse(exchange.getRequestMethod());
        String rawUri = exchange.getRequestURI().toString();
        String rawPath = exchange.getRequestURI().getRawPath();
        String path = decodePath(rawPath);
        Map<String, List<String>> queryParams = com.naqqa.elasticsearch.http.QueryStringParser.parse(exchange.getRequestURI().getRawQuery());
        HttpHeaders headers = new HttpHeaders();
        exchange.getRequestHeaders().forEach((name, values) -> values.forEach(value -> headers.add(name, value)));
        String contentEncoding = headers.getFirst("Content-Encoding");
        try {
            if (contentEncoding != null && contentEncoding.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
                body = CompressionUtil.gunzip(body);
            } else if (contentEncoding != null && contentEncoding.toLowerCase(java.util.Locale.ROOT).contains("deflate")) {
                body = CompressionUtil.inflate(body);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to decompress request body", e);
        }
        return new RestRequest(method, path, rawUri, queryParams, headers, body);
    }

    private static String decodePath(String rawPath) {
        StringBuilder sb = new StringBuilder();
        for (String segment : rawPath.split("/", -1)) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(com.naqqa.elasticsearch.http.UrlCodec.decode(segment, false));
        }
        return sb.toString();
    }

    private byte[] readLimited(java.io.InputStream in, long max) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > max) {
                throw new PayloadTooLargeException();
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private void writeResponse(HttpExchange exchange, RestRequest request, RestResponse response) throws IOException {
        byte[] body = response.content();
        CompressionUtil.Encoding encoding = CompressionUtil.negotiateResponseEncoding(request.header("Accept-Encoding"));
        boolean headOnly = request.method() == com.naqqa.elasticsearch.http.RestMethod.HEAD;
        byte[] finalBody = CompressionUtil.compress(body, encoding);
        String token = CompressionUtil.encodingToken(encoding);
        if (token != null && finalBody.length < body.length) {
            exchange.getResponseHeaders().add("Content-Encoding", token);
        } else {
            finalBody = body;
        }
        corsHandler.applyToResponse(request, response);
        if (response.contentType() != null) {
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
        }
        for (Map.Entry<String, List<String>> header : response.headers().asMap().entrySet()) {
            for (String value : header.getValue()) {
                exchange.getResponseHeaders().add(header.getKey(), value);
            }
        }
        exchange.sendResponseHeaders(response.status(), headOnly ? -1 : (finalBody.length == 0 ? -1 : finalBody.length));
        if (!headOnly && finalBody.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(finalBody);
            }
        }
    }

    private final class JdkRestChannel implements RestChannel {
        private final HttpExchange exchange;
        private final RestRequest request;

        private JdkRestChannel(HttpExchange exchange, RestRequest request) {
            this.exchange = exchange;
            this.request = request;
        }

        @Override
        public RestRequest request() {
            return request;
        }

        @Override
        public void sendResponse(RestResponse response) {
            try {
                writeResponse(exchange, request, response);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static final class PayloadTooLargeException extends RuntimeException implements com.naqqa.elasticsearch.http.RestStatusProvider {
        @Override
        public int restStatus() {
            return 413;
        }

        @Override
        public String getMessage() {
            return "request body exceeds maximum allowed size";
        }
    }
}
