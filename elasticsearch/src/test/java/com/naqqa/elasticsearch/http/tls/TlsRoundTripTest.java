package com.naqqa.elasticsearch.http.tls;

import com.naqqa.elasticsearch.http.HttpServerConfig;
import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.http.nio.NioHttpServerTransport;
import com.naqqa.elasticsearch.test.Assert;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TlsRoundTripTest {

    @com.naqqa.elasticsearch.test.Test
    public void httpsRequestOverNioTransportRoundTrips() throws Exception {
        Path keystore = Files.createTempFile("es-tls-test", ".p12");
        Files.deleteIfExists(keystore);
        char[] password = "changeit".toCharArray();

        boolean generated = generateSelfSignedKeystore(keystore, password);
        if (!generated) {
            return;
        }

        try {
            KeyStore keyStore = KeyStores.loadPkcs12(keystore, password);
            SSLContext serverContext = KeyStores.buildServerContext(keyStore, password);

            Router router = new Router();
            router.register(RestMethod.GET, "/secure", (req, ch) -> ch.sendResponse(RestResponse.text(200, "secure ok")));

            NioHttpServerTransport transport = new NioHttpServerTransport(
                new HttpServerConfig().host("127.0.0.1").port(0).sslContext(serverContext), router);
            transport.start();
            try {
                SSLContext clientContext = trustAllClientContext();
                SSLSocketFactory factory = clientContext.getSocketFactory();
                try (SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", transport.boundAddress().getPort())) {
                    socket.setSoTimeout(5000);
                    socket.startHandshake();
                    OutputStream out = socket.getOutputStream();
                    out.write(("GET /secure HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));

                    Map<String, String> headers = new LinkedHashMap<>();
                    String body = readResponseBody(socket.getInputStream(), headers);
                    Assert.assertEquals("secure ok", body);
                }
            } finally {
                transport.close();
            }
        } finally {
            Files.deleteIfExists(keystore);
        }
    }

    private static boolean generateSelfSignedKeystore(Path keystore, char[] password) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "es-test",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "3650",
                "-keystore", keystore.toString(),
                "-storetype", "PKCS12",
                "-storepass", new String(password),
                "-keypass", new String(password),
                "-dname", "CN=localhost, OU=Test, O=Test, L=Test, ST=Test, C=US"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (InputStream ignored = process.getInputStream()) {
                process.getInputStream().readAllBytes();
            }
            int code = process.waitFor();
            return code == 0 && Files.exists(keystore);
        } catch (Exception e) {
            return false;
        }
    }

    private static SSLContext trustAllClientContext() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, null);
        return context;
    }

    private static String readResponseBody(InputStream in, Map<String, String> headersOut) throws Exception {
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
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headersOut.put(lines[i].substring(0, colon).trim().toLowerCase(), lines[i].substring(colon + 1).trim());
            }
        }
        int contentLength = headersOut.containsKey("content-length") ? Integer.parseInt(headersOut.get("content-length")) : 0;
        byte[] body = new byte[contentLength];
        int off = 0;
        while (off < contentLength) {
            int n = in.read(body, off, contentLength - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        return new String(body, StandardCharsets.UTF_8);
    }
}
