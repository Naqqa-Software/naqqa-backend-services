package com.naqqa.elasticsearch.bench.amazon;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class AmazonCsvReader implements AutoCloseable {

    private final Reader in;
    private final char[] buf = new char[1 << 16];
    private int len = 0;
    private int pos = 0;
    private long rowsReturned = 0;

    public AmazonCsvReader(Path csvPath) throws IOException {
        this.in = new InputStreamReader(new FileInputStream(csvPath.toFile()), StandardCharsets.UTF_8);
    }

    public long rowsReturned() {
        return rowsReturned;
    }

    private int nextChar() throws IOException {
        if (pos >= len) {
            len = in.read(buf);
            pos = 0;
            if (len <= 0) {
                return -1;
            }
        }
        return buf[pos++];
    }

    private int peekChar() throws IOException {
        if (pos >= len) {
            len = in.read(buf);
            pos = 0;
            if (len <= 0) {
                return -1;
            }
        }
        return buf[pos];
    }

    public List<String> readRow() throws IOException {
        List<String> row = new ArrayList<>(16);
        StringBuilder field = new StringBuilder(64);
        boolean inQuotes = false;
        boolean any = false;
        while (true) {
            int ci = nextChar();
            if (ci == -1) {
                if (!any) {
                    return null;
                }
                row.add(field.toString());
                rowsReturned++;
                return row;
            }
            any = true;
            char c = (char) ci;
            if (inQuotes) {
                if (c == '"') {
                    int p = peekChar();
                    if (p == '"') {
                        field.append('"');
                        nextChar();
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"' && field.length() == 0) {
                inQuotes = true;
                continue;
            }
            if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                continue;
            }
            if (c == '\r') {
                continue;
            }
            if (c == '\n') {
                row.add(field.toString());
                rowsReturned++;
                return row;
            }
            field.append(c);
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
