package com.naqqa.elasticsearch.common.json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public final class NdJson {

    private NdJson() {
    }

    public static List<Object> readAll(String content) {
        List<Object> result = new ArrayList<>();
        Iterator<Object> it = iterate(new StringReader(content));
        while (it.hasNext()) {
            result.add(it.next());
        }
        return result;
    }

    public static List<Object> readAll(byte[] content) {
        return readAll(new String(content, StandardCharsets.UTF_8));
    }

    public static Iterator<Object> iterate(Reader reader) {
        return iterate(new BufferedReader(reader));
    }

    public static Iterator<Object> iterate(InputStream in) {
        return iterate(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    private static Iterator<Object> iterate(BufferedReader br) {
        return new Iterator<Object>() {
            private String nextLine;
            private boolean fetched;

            private void fetch() {
                if (fetched) {
                    return;
                }
                try {
                    String line;
                    do {
                        line = br.readLine();
                    } while (line != null && line.isBlank());
                    nextLine = line;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                fetched = true;
            }

            @Override
            public boolean hasNext() {
                fetch();
                return nextLine != null;
            }

            @Override
            public Object next() {
                fetch();
                if (nextLine == null) {
                    throw new NoSuchElementException();
                }
                String line = nextLine;
                fetched = false;
                nextLine = null;
                try (JsonParser parser = new JsonParser(line)) {
                    parser.nextToken();
                    return parser.readValue();
                }
            }
        };
    }

    public static String write(List<?> values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) {
            sb.append(JsonWriter.toJson(v, false));
            sb.append('\n');
        }
        return sb.toString();
    }

    public static void write(Writer out, List<?> values) {
        try {
            for (Object v : values) {
                out.write(JsonWriter.toJson(v, false));
                out.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] writeBytes(List<?> values) {
        return write(values).getBytes(StandardCharsets.UTF_8);
    }
}
