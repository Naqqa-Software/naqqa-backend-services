package com.naqqa.elasticsearch.http.jdk;

import com.naqqa.elasticsearch.http.HttpServerConfig;
import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.test.Assert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.zip.GZIPInputStream;

public final class JdkHttpServerTransportTest {

    @com.naqqa.elasticsearch.test.Test
    public void handlesBasicGetRequest() throws Exception {
        Router router = new Router();
        router.register(RestMethod.GET, "/hello/{name}", (req, ch) ->
            ch.sendResponse(RestResponse.text(200, "hello " + req.param("name"))));

        JdkHttpServerTransport transport = new JdkHttpServerTransport(
            new HttpServerConfig().host("127.0.0.1").port(0), router);
        transport.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + transport.boundAddress().getPort() + "/hello/world"))
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Assert.assertEquals(200, response.statusCode());
            Assert.assertEquals("hello world", response.body());
        } finally {
            transport.close();
        }
    }

    @com.naqqa.elasticsearch.test.Test
    public void returnsNotFoundForUnknownRoute() throws Exception {
        Router router = new Router();
        JdkHttpServerTransport transport = new JdkHttpServerTransport(
            new HttpServerConfig().host("127.0.0.1").port(0), router);
        transport.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + transport.boundAddress().getPort() + "/nope"))
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Assert.assertEquals(400, response.statusCode());
            Assert.assertTrue(response.body().contains("no handler found"));
        } finally {
            transport.close();
        }
    }

    @com.naqqa.elasticsearch.test.Test
    public void compressesResponseWhenGzipAccepted() throws Exception {
        String bigBody = "y".repeat(3000);
        Router router = new Router();
        router.register(RestMethod.GET, "/big", (req, ch) -> ch.sendResponse(RestResponse.text(200, bigBody)));

        JdkHttpServerTransport transport = new JdkHttpServerTransport(
            new HttpServerConfig().host("127.0.0.1").port(0), router);
        transport.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + transport.boundAddress().getPort() + "/big"))
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            Assert.assertEquals(200, response.statusCode());
            Assert.assertTrue(response.headers().firstValue("Content-Encoding").orElse("").contains("gzip"));
            byte[] decompressed;
            try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(response.body()))) {
                decompressed = in.readAllBytes();
            }
            Assert.assertEquals(bigBody, new String(decompressed, java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            transport.close();
        }
    }
}
