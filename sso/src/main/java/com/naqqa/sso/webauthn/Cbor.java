package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Cbor {
    public static final int MAX_DEPTH = 16;
    public static final int MAX_INPUT_BYTES = 65536;
    public static final int MAX_ITEMS = 4096;
    public static final int MAX_CONTAINER_ENTRIES = 1024;

    public enum Simple {
        NULL
    }

    public record Decoded(Object value, int end) {
    }

    private Cbor() {
    }

    public static Object decode(byte[] data) {
        Decoded d = decodeFirst(data, 0);
        if (d.end() != data.length) {
            throw new SsoException("webauthn.cbor.trailing");
        }
        return d.value();
    }

    public static Decoded decodeFirst(byte[] data, int offset) {
        if (data == null || offset < 0 || offset >= data.length) {
            throw new SsoException("webauthn.cbor.truncated");
        }
        if (data.length > MAX_INPUT_BYTES) {
            throw new SsoException("webauthn.cbor.limit");
        }
        Reader r = new Reader(data, offset);
        Object v = r.item(0);
        return new Decoded(v, r.pos);
    }

    @SuppressWarnings("unchecked")
    public static Map<Object, Object> asMap(Object value, String code) {
        if (value instanceof Map<?, ?> m) {
            return (Map<Object, Object>) m;
        }
        throw new SsoException(code);
    }

    private static final class Reader {
        private final byte[] data;
        private int pos;
        private int items;

        Reader(byte[] data, int pos) {
            this.data = data;
            this.pos = pos;
        }

        int u8() {
            if (pos >= data.length) {
                throw new SsoException("webauthn.cbor.truncated");
            }
            return data[pos++] & 0xFF;
        }

        long argument(int info) {
            if (info < 24) {
                return info;
            }
            int n;
            long min;
            switch (info) {
                case 24 -> {
                    n = 1;
                    min = 24;
                }
                case 25 -> {
                    n = 2;
                    min = 0x100;
                }
                case 26 -> {
                    n = 4;
                    min = 0x10000;
                }
                case 27 -> {
                    n = 8;
                    min = 0x100000000L;
                }
                case 31 -> throw new SsoException("webauthn.cbor.indefinite");
                default -> throw new SsoException("webauthn.cbor.malformed");
            }
            long v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 8) | u8();
            }
            if (n == 8 && v < 0) {
                throw new SsoException("webauthn.cbor.int_range");
            }
            if (v < min) {
                throw new SsoException("webauthn.cbor.non_canonical");
            }
            return v;
        }

        int length(int info) {
            long len = argument(info);
            if (len > data.length - pos) {
                throw new SsoException("webauthn.cbor.truncated");
            }
            return (int) len;
        }

        Object item(int depth) {
            if (++items > MAX_ITEMS) {
                throw new SsoException("webauthn.cbor.limit");
            }
            int ib = u8();
            int major = ib >>> 5;
            int info = ib & 0x1F;
            switch (major) {
                case 0:
                    return argument(info);
                case 1:
                    return -1L - argument(info);
                case 2: {
                    int len = length(info);
                    byte[] out = new byte[len];
                    System.arraycopy(data, pos, out, 0, len);
                    pos += len;
                    return out;
                }
                case 3: {
                    int len = length(info);
                    String s = utf8(data, pos, len);
                    pos += len;
                    return s;
                }
                case 4: {
                    if (depth + 1 > MAX_DEPTH) {
                        throw new SsoException("webauthn.cbor.depth");
                    }
                    int count = entries(info, 1);
                    List<Object> list = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        list.add(item(depth + 1));
                    }
                    return Collections.unmodifiableList(list);
                }
                case 5: {
                    if (depth + 1 > MAX_DEPTH) {
                        throw new SsoException("webauthn.cbor.depth");
                    }
                    int count = entries(info, 2);
                    Map<Object, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < count; i++) {
                        Object key = item(depth + 1);
                        if (!(key instanceof Long) && !(key instanceof String)) {
                            throw new SsoException("webauthn.cbor.map_key");
                        }
                        if (map.containsKey(key)) {
                            throw new SsoException("webauthn.cbor.duplicate_key");
                        }
                        map.put(key, item(depth + 1));
                    }
                    return Collections.unmodifiableMap(map);
                }
                case 6:
                    throw new SsoException("webauthn.cbor.tag");
                default:
                    return simple(info);
            }
        }

        int entries(int info, int perEntry) {
            long count = argument(info);
            if (count > MAX_CONTAINER_ENTRIES) {
                throw new SsoException("webauthn.cbor.limit");
            }
            if (count * perEntry > data.length - pos) {
                throw new SsoException("webauthn.cbor.truncated");
            }
            return (int) count;
        }

        Object simple(int info) {
            return switch (info) {
                case 20 -> Boolean.FALSE;
                case 21 -> Boolean.TRUE;
                case 22 -> Simple.NULL;
                case 25, 26, 27 -> throw new SsoException("webauthn.cbor.float");
                case 31 -> throw new SsoException("webauthn.cbor.indefinite");
                default -> throw new SsoException("webauthn.cbor.simple");
            };
        }
    }

    private static String utf8(byte[] data, int off, int len) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, off, len))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new SsoException("webauthn.cbor.utf8");
        }
    }

    public static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, value);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, Object value) {
        if (value instanceof Integer i) {
            writeInt(out, i.longValue());
        } else if (value instanceof Long l) {
            writeInt(out, l);
        } else if (value instanceof byte[] b) {
            head(out, 2, b.length);
            out.writeBytes(b);
        } else if (value instanceof String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            head(out, 3, b.length);
            out.writeBytes(b);
        } else if (value instanceof List<?> list) {
            head(out, 4, list.size());
            for (Object o : list) {
                write(out, o);
            }
        } else if (value instanceof Map<?, ?> map) {
            head(out, 5, map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                write(out, e.getKey());
                write(out, e.getValue());
            }
        } else if (value instanceof Boolean b) {
            out.write(b ? 0xF5 : 0xF4);
        } else if (value == null || value == Simple.NULL) {
            out.write(0xF6);
        } else {
            throw new SsoException("webauthn.cbor.unsupported_type");
        }
    }

    private static void writeInt(ByteArrayOutputStream out, long v) {
        if (v >= 0) {
            head(out, 0, v);
        } else {
            head(out, 1, -1L - v);
        }
    }

    private static void head(ByteArrayOutputStream out, int major, long v) {
        int m = major << 5;
        if (v < 24) {
            out.write(m | (int) v);
        } else if (v < 0x100) {
            out.write(m | 24);
            out.write((int) v);
        } else if (v < 0x10000) {
            out.write(m | 25);
            out.write((int) (v >>> 8));
            out.write((int) v);
        } else if (v < 0x100000000L) {
            out.write(m | 26);
            for (int s = 24; s >= 0; s -= 8) {
                out.write((int) (v >>> s));
            }
        } else {
            out.write(m | 27);
            for (int s = 56; s >= 0; s -= 8) {
                out.write((int) (v >>> s));
            }
        }
    }
}
