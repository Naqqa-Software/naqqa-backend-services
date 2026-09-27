package com.naqqa.elasticsearch.http.nio;

import com.naqqa.elasticsearch.http.HttpHeaders;
import com.naqqa.elasticsearch.http.RestChannel;
import com.naqqa.elasticsearch.http.RestErrors;
import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.http.RouteResult;
import com.naqqa.elasticsearch.http.tls.TlsSession;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Map;

final class TlsConnectionHandler implements Runnable {

    private final NioHttpServerTransport transport;
    private final SocketChannel channel;

    TlsConnectionHandler(NioHttpServerTransport transport, SocketChannel channel) {
        this.transport = transport;
        this.channel = channel;
    }

    @Override
    public void run() {
        SSLContext sslContext = transport.config().sslContext();
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(false);
        if (transport.config().clientAuthRequired()) {
            engine.setNeedClientAuth(true);
        }
        TlsSession session = new TlsSession(engine, channel);
        try {
            if (!session.handshake()) {
                channel.close();
                return;
            }
            runLoop(session);
        } catch (IOException e) {
            closeQuiet(session);
        }
    }

    private void runLoop(TlsSession session) throws IOException {
        byte[] buf = new byte[8192];
        int len = 0;
        while (true) {
            ParsedRequest parsed;
            while (true) {
                try {
                    parsed = HttpRequestParser.tryParse(buf, len, transport.config());
                } catch (HttpParseException e) {
                    RestRequest fallback = new RestRequest(RestMethod.GET, "/", "/", Map.of(), new HttpHeaders(), new byte[0]);
                    RestResponse response = RestErrors.fromException(fallback, transport.config().errorRenderer(), e);
                    ResponseBuilder.Encoded encoded = ResponseBuilder.encode(fallback, response, "HTTP/1.1", transport.config(), transport.corsHandler());
                    session.write(ByteBuffer.wrap(encoded.bytes()));
                    session.close();
                    return;
                }
                if (parsed != null) {
                    break;
                }
                if (len >= buf.length) {
                    byte[] bigger = new byte[buf.length * 2];
                    System.arraycopy(buf, 0, bigger, 0, len);
                    buf = bigger;
                }
                ByteBuffer tmp = ByteBuffer.wrap(buf, len, buf.length - len);
                int n = session.read(tmp);
                if (n < 0) {
                    session.close();
                    return;
                }
                len += n;
            }

            int consumed = parsed.consumedLength;
            RestRequest request;
            RestResponse response;
            String version = parsed.version;
            boolean close;
            try {
                request = com.naqqa.elasticsearch.http.nio.RequestFactory.build(parsed.method, parsed.rawTarget, parsed.headers, parsed.body);
                response = processSynchronously(request);
                ResponseBuilder.Encoded encoded = ResponseBuilder.encode(request, response, version, transport.config(), transport.corsHandler());
                session.write(ByteBuffer.wrap(encoded.bytes()));
                close = encoded.close();
            } catch (Exception e) {
                RestRequest fallback = new RestRequest(RestMethod.GET, "/", parsed.rawTarget, Map.of(), parsed.headers, new byte[0]);
                RestResponse errorResponse = RestErrors.fromException(fallback, transport.config().errorRenderer(), e);
                ResponseBuilder.Encoded encoded = ResponseBuilder.encode(fallback, errorResponse, version, transport.config(), transport.corsHandler());
                session.write(ByteBuffer.wrap(encoded.bytes()));
                close = true;
            }

            int remaining = len - consumed;
            if (remaining > 0) {
                System.arraycopy(buf, consumed, buf, 0, remaining);
            }
            len = remaining;
            if (close) {
                session.close();
                return;
            }
        }
    }

    private RestResponse processSynchronously(RestRequest request) throws Exception {
        var preflight = transport.corsHandler().handlePreflight(request);
        if (preflight.isPresent()) {
            return preflight.get();
        }
        RouteResult result = transport.router().route(request.method(), request.path());
        switch (result.outcome()) {
            case MATCHED: {
                request.setPathParams(result.pathParams());
                SyncChannel sync = new SyncChannel(request);
                try {
                    result.handler().handleRequest(request, sync);
                } catch (Exception e) {
                    return RestErrors.fromException(request, transport.config().errorRenderer(), e);
                }
                return sync.response();
            }
            case METHOD_NOT_ALLOWED:
                return RestErrors.methodNotAllowed(request, result.allowedMethods());
            default:
                return RestErrors.noHandlerFound(request, request.rawUri(), request.method());
        }
    }

    private void closeQuiet(TlsSession session) {
        try {
            session.close();
        } catch (IOException ignored) {
        }
    }

    private static final class SyncChannel implements RestChannel {
        private final RestRequest request;
        private RestResponse response;

        SyncChannel(RestRequest request) {
            this.request = request;
        }

        @Override
        public RestRequest request() {
            return request;
        }

        @Override
        public void sendResponse(RestResponse response) {
            this.response = response;
        }

        RestResponse response() {
            return response;
        }
    }
}
