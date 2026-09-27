package com.naqqa.elasticsearch.http.nio;

import com.naqqa.elasticsearch.http.HttpServerConfig;
import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.test.Assert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class NioHttpServerTransportTest {

    @com.naqqa.elasticsearch.test.Test
    public void keepAlivePipeliningOrderIsPreserved() throws Exception {
        Router router = new Router();
        router.register(RestMethod.GET, "/fast", (req, ch) -> ch.sendResponse(RestResponse.text(200, "fast")));
        router.register(RestMethod.GET, "/slow", (req, ch) -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }
            ch.sendResponse(RestResponse.text(200, "slow"));
        });

        NioHttpServerTransport transport = new NioHttpServerTransport(
            new HttpServerConfig().host("127.0.0.1").port(0), router);
        transport.start();
        try (Socket socket = new Socket("127.0.0.1", transport.boundAddress().getPort())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(buildRequest("GET", "/fast", Map.of("Connection", "keep-alive"), null));
            SimpleResponse r1 = readResponse(in);
            Assert.assertEquals(200, r1.status);
            Assert.assertEquals("fast", r1.bodyAsString());

            out.write(buildRequest("GET", "/slow", Map.of("Connection", "keep-alive"), null));
            out.write(buildRequest("GET", "/fast", Map.of("Connection", "close"), null));

            SimpleResponse r2 = readResponse(in);
            SimpleResponse r3 = readResponse(in);
            Assert.assertEquals("slow", r2.bodyAsString());
            Assert.assertEquals("fast", r3.bodyAsString());

            int eof = in.read();
            Assert.assertEquals(-1, eof);
        } finally {
            transport.close();
        }
    }

    @com.naqqa.elasticsearch.test.Test
    public void chunkedRequestBodyIsDecoded() throws Exception {
        Router router = new Router();
        router.register(RestMethod.POST, "/echo", (req, ch) -> ch.sendResponse(RestResponse.text(200, req.contentAsString())));

        NioHttpServerTransport transport = new NioHttpServerTransport(
            new HttpServerConfig().host("127.0.0.1").port(0), router);
        transport.start();
        try (Socket socket = new Socket("127.0.0.1", transport.boundAddress().getPort())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            StringBuilder request = new StringBuilder();
            request.append("POST /echo HTTP/1.1\r\n");
            request.append("Host: localhost\r\n");
            request.append("Transfer-Encoding: chunked\r\n");
            request.append("Connection: close\r\n\r\n");
            request.append("6\r\nhello \r\n");
            request.append("5\r\nworld\r\n");
            request.append("0\r\n\r\n");
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));

            SimpleResponse response = readResponse(socket.getInputStream());
            Assert.assertEquals(200, response.status);
            Assert.assertEquals("hello world", response.bodyAsString());
        } finally {
            transport.close();
        }
    }

    @com.naqqa.elasticsearch.test.Test
    public void gzipResponseAndRequestCompressionRoundTrip() throws Exception {
        String bigText = "x".repeat(2000);
        Router router = new Router();
        router.register(RestMethod.GET, "/big", (req, ch) -> ch.sendResponse(RestResponse.text(200, bigText)));
        router.register(RestMethod.POST, "/echo", (req, ch) -> ch.sendResponse(RestResponse.text(200, req.contentAsString())));

        NioHttpServerTransport transport = new NioHttpServerTransport(
            new HttpServerConfig().host("127.0.0.1").port(0), router);
        transport.start();
        try (Socket socket = new Socket("127.0.0.1", transport.boundAddress().getPort())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept-Encoding", "gzip");
            headers.put("Connection", "keep-alive");
            out.write(buildRequest("GET", "/big", headers, null));
            SimpleResponse response = readResponse(in);
            Assert.assertEquals(200, response.status);
            Assert.assertEquals("gzip", response.headers.get("content-encoding"));
            byte[] decompressed = gunzip(response.body);
            Assert.assertEquals(bigText, new String(decompressed, StandardCharsets.UTF_8));

            byte[] compressedBody = gzip("hello gzip".getBytes(StandardCharsets.UTF_8));
            Map<String, String> reqHeaders = new LinkedHashMap<>();
            reqHeaders.put("Content-Encoding", "gzip");
            reqHeaders.put("Accept-Encoding", "identity");
            reqHeaders.put("Connection", "close");
            out.write(buildRequest("POST", "/echo", reqHeaders, compressedBody));
            SimpleResponse echoResponse = readResponse(in);
            Assert.assertEquals("hello gzip", echoResponse.bodyAsString());
        } finally {
            transport.close();
        }
    }

    @com.naqqa.elasticsearch.test.Test
    public void oversizedBodyReturns413AndClosesConnection() throws Exception {
        Router router = new Router();
        router.register(RestMethod.POST, "/echo", (req, ch) -> ch.sendResponse(RestResponse.text(200, req.contentAsString())));

        NioHttpServerTransport transport = new NioHttpServerTransport(
            new HttpServerConfig().host("127.0.0.1").port(0).maxBodyBytes(10), router);
        transport.start();
        try (Socket socket = new Socket("127.0.0.1", transport.boundAddress().getPort())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            byte[] body = "this body is definitely more than ten bytes".getBytes(StandardCharsets.UTF_8);
            out.write(buildRequest("POST", "/echo", Map.of(), body));

            SimpleResponse response = readResponse(socket.getInputStream());
            Assert.assertEquals(413, response.status);

            int eof = socket.getInputStream().read();
            Assert.assertEquals(-1, eof);
        } finally {
            transport.close();
        }
    }

    private static byte[] buildRequest(String method, String path, Map<String, String> headers, byte[] body) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: localhost\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            sb.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        if (body != null) {
            sb.append("Content-Length: ").append(body.length).append("\r\n");
        }
        sb.append("\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.US_ASCII);
        if (body == null) {
            return head;
        }
        byte[] full = new byte[head.length + body.length];
        System.arraycopy(head, 0, full, 0, head.length);
        System.arraycopy(body, 0, full, head.length, body.length);
        return full;
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            return in.readAllBytes();
        }
    }

    private static final class SimpleResponse {
        int status;
        Map<String, String> headers;
        byte[] body;

        String bodyAsString() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static SimpleResponse readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int c0 = -1, c1 = -1, c2 = -1, c3 = -1;
        int b;
        while ((b = in.read()) != -1) {
            headerBuf.write(b);
            c0 = c1;
            c1 = c2;
            c2 = c3;
            c3 = b;
            if (c0 == '\r' && c1 == '\n' && c2 == '\r' && c3 == '\n') {
                break;
            }
        }
        String headerText = headerBuf.toString(StandardCharsets.ISO_8859_1);
        String[] lines = headerText.split("\r\n");
        SimpleResponse response = new SimpleResponse();
        response.status = Integer.parseInt(lines[0].trim().split(" ")[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
        }
        response.headers = headers;
        int contentLength = headers.containsKey("content-length") ? Integer.parseInt(headers.get("content-length")) : 0;
        byte[] body = new byte[contentLength];
        int off = 0;
        while (off < contentLength) {
            int n = in.read(body, off, contentLength - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        response.body = body;
        return response;
    }
}
