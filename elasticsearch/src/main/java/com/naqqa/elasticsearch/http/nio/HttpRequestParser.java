package com.naqqa.elasticsearch.http.nio;

import com.naqqa.elasticsearch.http.HttpHeaders;
import com.naqqa.elasticsearch.http.HttpServerConfig;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class HttpRequestParser {

    private HttpRequestParser() {
    }

    public static ParsedRequest tryParse(byte[] buf, int len, HttpServerConfig config) {
        int requestLineEnd = indexOfCrlf(buf, 0, len);
        if (requestLineEnd < 0) {
            if (len > config.maxInitialLineLength()) {
                throw new HttpParseException(414, "request line exceeds maximum length");
            }
            return null;
        }
        if (requestLineEnd > config.maxInitialLineLength()) {
            throw new HttpParseException(414, "request line exceeds maximum length");
        }
        String requestLine = new String(buf, 0, requestLineEnd, StandardCharsets.ISO_8859_1);
        String[] parts = requestLine.split(" ");
        if (parts.length != 3) {
            throw new HttpParseException(400, "invalid request line [" + requestLine + "]");
        }
        String method = parts[0];
        String rawTarget = parts[1];
        String version = parts[2];
        if (!version.startsWith("HTTP/")) {
            throw new HttpParseException(400, "invalid HTTP version [" + version + "]");
        }

        int headerStart = requestLineEnd + 2;
        int headersEnd = indexOfDoubleCrlf(buf, headerStart, len);
        if (headersEnd < 0) {
            if (len - headerStart > config.maxHeaderBytes()) {
                throw new HttpParseException(400, "request headers exceed maximum size");
            }
            return null;
        }
        if (headersEnd - headerStart > config.maxHeaderBytes()) {
            throw new HttpParseException(400, "request headers exceed maximum size");
        }

        HttpHeaders headers = new HttpHeaders();
        int count = 0;
        int lineStart = headerStart;
        while (lineStart < headersEnd) {
            int lineEnd = indexOfCrlf(buf, lineStart, headersEnd + 2);
            if (lineEnd < 0 || lineEnd > headersEnd) {
                lineEnd = headersEnd;
            }
            if (lineEnd > lineStart) {
                String line = new String(buf, lineStart, lineEnd - lineStart, StandardCharsets.ISO_8859_1);
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    throw new HttpParseException(400, "invalid header line [" + line + "]");
                }
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                headers.add(name, value);
                count++;
                if (count > config.maxHeaderCount()) {
                    throw new HttpParseException(400, "too many request headers");
                }
            }
            lineStart = lineEnd + 2;
        }

        int bodyStart = headersEnd + 4;
        String contentLengthHeader = headers.getFirst("Content-Length");
        String transferEncoding = headers.getFirst("Transfer-Encoding");
        boolean chunked = transferEncoding != null && transferEncoding.toLowerCase(java.util.Locale.ROOT).contains("chunked");

        if (chunked && contentLengthHeader != null) {
            throw new HttpParseException(400, "request has both Content-Length and chunked Transfer-Encoding");
        }

        byte[] body;
        int consumedLength;
        if (chunked) {
            ChunkedResult result = parseChunked(buf, bodyStart, len, config.maxBodyBytes());
            if (result == null) {
                return null;
            }
            body = result.body;
            consumedLength = result.consumedLength;
        } else if (contentLengthHeader != null) {
            long contentLength;
            try {
                contentLength = Long.parseLong(contentLengthHeader.trim());
            } catch (NumberFormatException e) {
                throw new HttpParseException(400, "invalid Content-Length header");
            }
            if (contentLength < 0) {
                throw new HttpParseException(400, "invalid Content-Length header");
            }
            if (contentLength > config.maxBodyBytes()) {
                throw new HttpParseException(413, "request body exceeds maximum allowed size");
            }
            if (len - bodyStart < contentLength) {
                return null;
            }
            body = new byte[(int) contentLength];
            System.arraycopy(buf, bodyStart, body, 0, (int) contentLength);
            consumedLength = bodyStart + (int) contentLength;
        } else {
            body = new byte[0];
            consumedLength = bodyStart;
        }

        return new ParsedRequest(method, rawTarget, version, headers, body, consumedLength);
    }

    private record ChunkedResult(byte[] body, int consumedLength) {
    }

    private static ChunkedResult parseChunked(byte[] buf, int start, int len, long maxBodyBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = start;
        while (true) {
            int lineEnd = indexOfCrlf(buf, pos, len);
            if (lineEnd < 0) {
                return null;
            }
            String sizeLine = new String(buf, pos, lineEnd - pos, StandardCharsets.ISO_8859_1);
            int semicolon = sizeLine.indexOf(';');
            String sizeToken = semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine;
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(sizeToken.trim(), 16);
            } catch (NumberFormatException e) {
                throw new HttpParseException(400, "invalid chunk size [" + sizeToken + "]");
            }
            if (chunkSize < 0) {
                throw new HttpParseException(400, "invalid chunk size [" + sizeToken + "]");
            }
            int dataStart = lineEnd + 2;
            if (chunkSize == 0) {
                if (dataStart + 1 >= len) {
                    return null;
                }
                if (buf[dataStart] == '\r' && buf[dataStart + 1] == '\n') {
                    return new ChunkedResult(out.toByteArray(), dataStart + 2);
                }
                int trailerEnd = indexOfDoubleCrlf(buf, dataStart, len);
                if (trailerEnd < 0) {
                    return null;
                }
                return new ChunkedResult(out.toByteArray(), trailerEnd + 4);
            }
            if (out.size() + chunkSize > maxBodyBytes) {
                throw new HttpParseException(413, "request body exceeds maximum allowed size");
            }
            int dataEnd = dataStart + chunkSize;
            if (dataEnd + 2 > len) {
                return null;
            }
            out.write(buf, dataStart, chunkSize);
            if (buf[dataEnd] != '\r' || buf[dataEnd + 1] != '\n') {
                throw new HttpParseException(400, "malformed chunk terminator");
            }
            pos = dataEnd + 2;
        }
    }

    private static int indexOfCrlf(byte[] buf, int from, int limit) {
        for (int i = from; i + 1 < limit; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfDoubleCrlf(byte[] buf, int from, int limit) {
        for (int i = from; i + 3 < limit; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
