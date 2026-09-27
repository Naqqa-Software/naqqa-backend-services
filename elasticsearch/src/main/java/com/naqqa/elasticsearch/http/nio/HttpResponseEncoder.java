package com.naqqa.elasticsearch.http.nio;

import com.naqqa.elasticsearch.http.HttpHeaders;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

final class HttpResponseEncoder {

    private HttpResponseEncoder() {
    }

    static byte[] encode(int status, HttpHeaders headers, byte[] body, boolean omitBody) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 256);
        writeAscii(out, "HTTP/1.1 " + status + " " + HttpStatusPhrases.forStatus(status) + "\r\n");
        for (Map.Entry<String, List<String>> header : headers.asMap().entrySet()) {
            for (String value : header.getValue()) {
                writeAscii(out, header.getKey() + ": " + value + "\r\n");
            }
        }
        writeAscii(out, "\r\n");
        if (!omitBody && body.length > 0) {
            out.write(body, 0, body.length);
        }
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        out.write(bytes, 0, bytes.length);
    }
}
